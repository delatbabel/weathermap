package org.weathermap.osm;

import org.weathermap.model.BoundingBox;
import org.weathermap.util.Cache;
import org.weathermap.util.Http;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Fetches base-map features from an Overpass API instance.
 *
 * <p>Overpass is the right tool for "give me the coastline and the boundaries
 * inside this rectangle": it queries the live OSM database by tag and bounding
 * box and returns only the matching elements. The alternative - downloading a
 * regional {@code .osm.pbf} extract and filtering it locally - moves gigabytes
 * to answer a question about one country.</p>
 *
 * <h2>Being a good citizen</h2>
 *
 * <p>The public instances are donated capacity with a published usage policy,
 * and this client is built to stay inside it:</p>
 *
 * <ul>
 *   <li>results are <b>cached for {@link Cache#OSM_TTL}</b>, so panning back to a
 *       region costs nothing;</li>
 *   <li>each query carries a {@code [timeout:...]} and {@code [maxsize:...]} so a
 *       runaway request is rejected by the server rather than absorbed;</li>
 *   <li>the {@link Http#USER_AGENT} identifies the application - anonymous
 *       clients are blocked;</li>
 *   <li>only the requested {@link FeatureKind}s are asked for.</li>
 * </ul>
 *
 * <p><b>TODO:</b> a very large box will still exceed the server limit. The fix
 * is to tile the request - split the box into sub-boxes of at most a few degrees
 * and merge the results - which needs coastline segments to be stitched across
 * tile seams. See README.md.</p>
 */
public final class OverpassClient implements OsmSource {

    private static final Logger LOG = Logger.getLogger(OverpassClient.class.getName());

    /**
     * The public instances, tried in order until one answers.
     *
     * <p>Failover is not defensive padding, it is load-bearing. These are
     * donated instances that time out, return 504 and go down for maintenance
     * as a matter of routine - all three behaviours were seen within one hour
     * of testing. And {@code overpass-api.de} publishes two A records, of which
     * one was black-holing connections from the test machine; Java's
     * {@code HttpClient} picks one address and does not fall back, so a single
     * endpoint failed about half the time for a reason that had nothing to do
     * with Overpass at all.</p>
     *
     * <p>Whichever endpoint answers is remembered and tried first next time.</p>
     */
    public static final List<String> DEFAULT_ENDPOINTS = List.of(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter");

    /** @deprecated prefer {@link #DEFAULT_ENDPOINTS}; kept for single-endpoint callers. */
    @Deprecated
    public static final String DEFAULT_ENDPOINT = DEFAULT_ENDPOINTS.get(0);

    /** Server-side seconds before the query is abandoned. */
    private static final int QUERY_TIMEOUT_SECONDS = 180;

    /**
     * The widest box worth asking Overpass about at all, in degrees.
     *
     * <p>Above this the query is not merely slow, it is pointless. Cost grows
     * with the area of the box while the benefit shrinks with its scale: across
     * twenty degrees rendered into sixteen hundred pixels, full-resolution OSM
     * coastline lands several nodes to the pixel, so tens of megabytes are spent
     * drawing something indistinguishable from the bundled Natural Earth
     * outline. Asking a donated server for that is rude as well as useless.</p>
     *
     * <p>A 56 degree box over South-East Asia is what prompted this: it timed
     * out through all three instances in turn - about ninety seconds - and then
     * fell back to the outline it should have started from.</p>
     *
     * <p>This is a floor on quality, not a ceiling on ambition: the real fix for
     * a large area is to tile the request, which is the TODO above.</p>
     */
    public static final double MAX_SERVABLE_SPAN = 20.0;

    /** True when {@code bbox} is too large for Overpass to be worth asking. */
    public static boolean isTooLarge(org.weathermap.model.BoundingBox bbox) {
        return Math.max(bbox.widthDegrees(), bbox.heightDegrees()) > MAX_SERVABLE_SPAN;
    }

    /**
     * Server-side memory ceiling for one query, in bytes.
     *
     * <p>Was 512 MB, which was simply asking for trouble: it invites the server
     * to spend half a gigabyte on one client's request, and a coastline query
     * for Scotland genuinely returned 70 MB of XML before the instance started
     * answering 429 to everything. A ceiling this size makes an over-broad query
     * fail fast and cheaply instead of succeeding expensively.</p>
     */
    private static final long MAX_QUERY_SIZE = 64L * 1024 * 1024;

    private final List<URI> endpoints;
    private final Cache cache;

    /** The endpoint that answered last, tried first next time. */
    private volatile URI preferred;

    public OverpassClient() {
        this(DEFAULT_ENDPOINTS.stream().map(URI::create).toList(), new Cache());
    }

    public OverpassClient(URI endpoint, Cache cache) {
        this(List.of(endpoint), cache);
    }

    public OverpassClient(List<URI> endpoints, Cache cache) {
        if (endpoints.isEmpty()) throw new IllegalArgumentException("need an endpoint");
        this.endpoints = List.copyOf(endpoints);
        this.cache = cache;
        this.preferred = this.endpoints.get(0);
    }

    @Override
    public String description() {
        return "Overpass at " + preferred.getHost();
    }

    @Override
    public List<Feature> fetch(BoundingBox bbox, List<FeatureKind> kinds)
            throws IOException, InterruptedException {
        if (kinds.isEmpty()) return List.of();

        final String query = buildQuery(bbox, kinds);

        // Keyed on the query alone, not the endpoint: every instance serves the
        // same OSM database, so a result cached from one is valid for all, and
        // keying on the endpoint would refetch whenever failover moved us.
        final Path entry = cache.pathFor("osm", query, ".osm.xml");

        if (cache.isFresh(entry, Cache.OSM_TTL)) {
            LOG.fine(() -> "OSM cache hit: " + entry);
        }
        else {
            final String xml = queryWithFailover(query, bbox);
            Files.createDirectories(entry.getParent());
            Files.writeString(entry, xml, StandardCharsets.UTF_8);
        }

        try (var in = Files.newInputStream(entry)) {
            return new OsmXmlParser().parse(in);
        }
    }

    /**
     * Rejects a response that is an Overpass error report rather than data.
     *
     * <p><b>Overpass reports a failed query with HTTP 200.</b> A query that
     * times out server-side comes back as a well-formed OSM document whose only
     * content is
     * {@code <remark> runtime error: Query timed out ... </remark>} - 360 bytes,
     * status 200, parses perfectly, and contains nothing.</p>
     *
     * <p>Without this check that document was treated as a successful empty
     * result and <b>written to the cache, where it stayed valid for four
     * weeks</b>. The area it covered then rendered with no base map at all, on
     * every subsequent run, with nothing in the logs to say why - the request
     * was never made again. Two such entries were found in a real cache.</p>
     *
     * <p>Treating it as a failure means failover moves to the next instance and
     * nothing is cached, which is what a timeout should do.</p>
     */
    private static void rejectErrorDocument(String xml, URI uri) throws IOException {
        final int remark = xml.indexOf("<remark>");
        if (remark < 0) return;
        final int end = xml.indexOf("</remark>", remark);
        final String message = (end > remark)
                ? xml.substring(remark + "<remark>".length(), end).trim()
                : "unspecified";
        throw new IOException(uri.getHost() + " reported: " + message);
    }

    /** Tries each endpoint once, preferred first, until one answers. */
    private String queryWithFailover(String query, BoundingBox bbox) throws IOException,
            InterruptedException {
        final List<URI> order = new ArrayList<>();
        order.add(preferred);
        for (URI uri : endpoints) {
            if (!uri.equals(preferred)) order.add(uri);
        }

        IOException last = null;
        for (URI uri : order) {
            try {
                LOG.info(() -> "Querying Overpass at " + uri.getHost() + " for " + bbox);
                final String xml = Http.postFormOnce(uri, "data=" + Http.encode(query));
                rejectErrorDocument(xml, uri);
                preferred = uri;
                return xml;
            }
            catch (IOException e) {
                LOG.fine(() -> uri.getHost() + " did not answer: " + e);
                last = e;
            }
        }
        throw new IOException("no Overpass instance answered (last: "
                + (last == null ? "unknown" : last.getMessage()) + ")", last);
    }

    /**
     * Builds the Overpass QL for the requested kinds.
     *
     * <p>{@code out body; >; out skel qt;} is the standard recursion: print the
     * matched elements, then recurse down to the nodes that give ways their
     * geometry, then print those in a minimal form. Without the recursion the
     * ways come back as lists of node ids with no coordinates.</p>
     */
    String buildQuery(BoundingBox bbox, List<FeatureKind> kinds) {
        final String bboxFilter = "(" + bbox.toOverpassBbox() + ")";
        final List<String> clauses = new ArrayList<>();

        for (FeatureKind kind : kinds) {
            switch (kind) {
                case COASTLINE -> clauses.add("  way[\"natural\"=\"coastline\"]" + bboxFilter + ";");
                case BOUNDARY -> {
                    // admin_level is filtered at render time, not here, so one
                    // download can serve national-only and detailed styles.
                    clauses.add("  way[\"boundary\"=\"administrative\"][\"admin_level\"~\"^[2-4]$\"]"
                            + bboxFilter + ";");
                }
                case PLACE -> clauses.add(
                        "  node[\"place\"~\"^(" + placeTypesFor(bbox) + ")$\"]"
                                + bboxFilter + ";");
            }
        }

        // The recursion is only needed when ways were asked for: it exists to
        // turn node references into coordinates. A places-only query returns
        // nodes that already carry their own, and asking for it anyway makes the
        // server do real work for nothing.
        final boolean needsWays = kinds.contains(FeatureKind.COASTLINE)
                || kinds.contains(FeatureKind.BOUNDARY);

        return "[out:xml][timeout:" + QUERY_TIMEOUT_SECONDS + "][maxsize:" + MAX_QUERY_SIZE + "];\n"
                + "(\n" + String.join("\n", clauses) + "\n);\n"
                + "out body;\n" + (needsWays ? ">;\nout skel qt;\n" : "");
    }

    /**
     * Which {@code place=} values to ask for, given how much ground the query
     * covers.
     *
     * <p>Hamlets over four degrees of Scotland are thousands of nodes that the
     * renderer then throws away, because
     * {@code VectorLayers.PlaceLabelLayer} thins labels by the same rule before
     * drawing them. Matching the query to what will actually be drawn is the
     * difference between half a megabyte and several.</p>
     */
    static String placeTypesFor(BoundingBox bbox) {
        final double span = Math.max(bbox.widthDegrees(), bbox.heightDegrees());
        if (span > 10) return "city";
        if (span > 2) return "city|town";
        if (span > 0.75) return "city|town|village";
        return "city|town|village|hamlet";
    }
}
