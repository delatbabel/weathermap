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

    /** The main public instance. {@code overpass.kumi.systems} is a mirror. */
    public static final String DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter";

    /** Server-side seconds before the query is abandoned. */
    private static final int QUERY_TIMEOUT_SECONDS = 180;

    /** Server-side memory ceiling for one query, in bytes. */
    private static final long MAX_QUERY_SIZE = 512L * 1024 * 1024;

    private final URI endpoint;
    private final Cache cache;

    public OverpassClient() {
        this(URI.create(DEFAULT_ENDPOINT), new Cache());
    }

    public OverpassClient(URI endpoint, Cache cache) {
        this.endpoint = endpoint;
        this.cache = cache;
    }

    @Override
    public String description() {
        return "Overpass API at " + endpoint.getHost();
    }

    @Override
    public List<Feature> fetch(BoundingBox bbox, List<FeatureKind> kinds)
            throws IOException, InterruptedException {
        if (kinds.isEmpty()) return List.of();

        final String query = buildQuery(bbox, kinds);
        final Path entry = cache.pathFor("osm", endpoint + "\n" + query, ".osm.xml");

        if (cache.isFresh(entry, Cache.OSM_TTL)) {
            LOG.fine(() -> "OSM cache hit: " + entry);
        }
        else {
            LOG.info(() -> "Querying " + description() + " for " + bbox);
            final String xml = Http.postForm(endpoint, "data=" + Http.encode(query));
            Files.createDirectories(entry.getParent());
            Files.writeString(entry, xml, StandardCharsets.UTF_8);
        }

        try (var in = Files.newInputStream(entry)) {
            return new OsmXmlParser().parse(in);
        }
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
                        "  node[\"place\"~\"^(city|town|village|hamlet)$\"]" + bboxFilter + ";");
            }
        }

        return "[out:xml][timeout:" + QUERY_TIMEOUT_SECONDS + "][maxsize:" + MAX_QUERY_SIZE + "];\n"
                + "(\n" + String.join("\n", clauses) + "\n);\n"
                + "out body;\n>;\nout skel qt;\n";
    }
}
