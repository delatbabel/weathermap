package org.weathermap.grib;

import org.weathermap.model.BoundingBox;
import org.weathermap.model.ChartRequest;
import org.weathermap.model.GribLevel;
import org.weathermap.model.GribModel;
import org.weathermap.model.GribSelection;
import org.weathermap.model.GribVariable;
import org.weathermap.util.Cache;
import org.weathermap.util.Http;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Downloads GRIB subsets from NOAA's NOMADS {@code filter_*.pl} services.
 *
 * <h2>Why the filter endpoints</h2>
 *
 * <p>NOMADS publishes whole model output as single files - a GFS 0.25&deg;
 * forecast hour is around 500 MB. The {@code filter_*.pl} CGI in front of it
 * cuts a file down to the requested variables, levels and geographic subregion
 * server-side, and the same field for one country is tens of kilobytes.</p>
 *
 * <p>That is why {@link BoundingBox} threads through the whole application:
 * the rectangle is not only what gets drawn, it is what gets transferred.</p>
 *
 * <h2>The request</h2>
 *
 * <pre>
 * https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl
 *     ?file=gfs.t00z.pgrb2.0p25.f000
 *     &amp;var_TMP=on
 *     &amp;lev_2_m_above_ground=on
 *     &amp;subregion=
 *     &amp;leftlon=-11&amp;rightlon=2&amp;toplat=61&amp;bottomlat=49.5
 *     &amp;dir=%2Fgfs.20260911%2F00%2Fatmos
 * </pre>
 *
 * <p>Note {@code subregion=} with an empty value: it is a flag, and omitting it
 * makes the corner parameters ineligible and returns the global field.</p>
 *
 * <h2>The antimeridian</h2>
 *
 * <p>{@code leftlon} must be west of {@code rightlon}, so a Pacific box that
 * runs through 180&deg; cannot be asked for in one request. It is asked for in
 * two - {@link BoundingBox#halves} - and the fields they decode to are put back
 * together by {@link Grid#join} before anything is drawn. Two requests rather
 * than one whole-band request: the band would be several times the bytes for a
 * subregion of the same chart, off a service that asks to be used sparingly.</p>
 *
 * <h2>Failure modes worth knowing</h2>
 *
 * <ul>
 *   <li><b>A 200 with a tiny body is the common failure.</b> If the variable is
 *       not published at the requested level the filter matches no records and
 *       returns an empty or near-empty file rather than an error status.
 *       {@link Http#download} rejects a zero-length body; a non-empty file that
 *       holds no GRIB messages is caught by {@link Grib2Scanner#scan}.</li>
 *   <li><b>A run that is still being written</b> returns 404 for later forecast
 *       hours. {@link GribSelection#PUBLICATION_LAG} is what avoids asking.</li>
 *   <li>NOMADS throttles aggressively by client address; requests are made in
 *       sequence, never in parallel.</li>
 * </ul>
 */
public final class NomadsClient implements GribSource {

    private static final Logger LOG = Logger.getLogger(NomadsClient.class.getName());

    public static final String DEFAULT_BASE = "https://nomads.ncep.noaa.gov/cgi-bin/";

    private final String base;
    private final Cache cache;

    public NomadsClient() {
        this(DEFAULT_BASE, new Cache());
    }

    public NomadsClient(String base, Cache cache) {
        this.base = base.endsWith("/") ? base : base + "/";
        this.cache = cache;
    }

    @Override
    public String description() {
        return "NOAA NOMADS at " + URI.create(base).getHost();
    }

    @Override
    public List<Downloaded> download(BoundingBox bbox, GribSelection selection,
                                    List<ChartRequest> requests, Http.ProgressListener listener)
            throws IOException, InterruptedException {

        final List<Downloaded> out = new ArrayList<>();
        final List<ChartRequest> missing = new ArrayList<>();
        for (ChartRequest request : requests) {
            if (listener != null && listener.isCancelled()) break;

            final String yyyymmdd = request.runDate().format(RUN_DATE);
            final int cycle = request.cycle();
            final int forecastHour = request.forecastHour();

            // One request per side of the antimeridian. A box that does not
            // cross it has one half and so makes one request, which is the
            // ordinary case and costs nothing extra.
            final List<Path> parts = new ArrayList<>();
            boolean rolledOff = false;
            for (BoundingBox half : bbox.halves()) {
                final URI uri = buildUri(half, selection, yyyymmdd, cycle, forecastHour);

                // A published run is immutable, so a cache hit needs no expiry
                // check beyond "is it there and non-empty".
                final Path entry = cache.pathFor("grib", uri.toString(), ".grib2");
                if (cache.isFresh(entry, null)) {
                    LOG.fine(() -> "GRIB cache hit: " + entry);
                }
                else {
                    LOG.info(() -> "Downloading " + selection.model().displayName()
                            + " " + yyyymmdd + " " + cycle + "Z f" + forecastHour
                            + " for " + half);
                    try {
                        Http.download(uri, entry, listener);
                    }
                    catch (Http.RateLimitedException e) {
                        throw e;                     // not this chart's fault; stop
                    }
                    catch (IOException e) {
                        // A run that has aged out of the archive answers 404, and
                        // one chart of a week ago being gone is no reason to throw
                        // away the rest of the series. Anything else - a broken
                        // connection, a server error - would fail every remaining
                        // chart too, so it still stops the run.
                        if (!hasRolledOff(e)) throw e;
                        LOG.warning("No longer in the archive, skipping: " + request);
                        rolledOff = true;
                        break;
                    }
                }
                parts.add(entry);
            }
            if (rolledOff) {
                // Half a chart is not a chart: if either side of the seam has
                // gone, the whole request goes with it.
                missing.add(request);
                continue;
            }
            out.add(new Downloaded(request, parts));
        }

        if (!missing.isEmpty()) {
            LOG.warning(missing.size() + " chart(s) skipped: their runs have rolled off "
                    + "NOMADS, the oldest wanted being " + missing.get(0));
        }
        if (out.isEmpty() && !requests.isEmpty()) {
            throw new IOException("none of the " + requests.size()
                    + " requested charts are still in the archive; "
                    + "ask for a more recent window");
        }
        return out;
    }

    /**
     * True for the archive having aged a run out rather than a transient fault.
     *
     * <p>NOMADS answers a request for a run it no longer holds with a 404. That
     * is a fact about the past, not a failure, and the difference matters: one
     * is skipped and the rest of the series proceeds, the other stops it.</p>
     */
    private static boolean hasRolledOff(IOException e) {
        final String message = String.valueOf(e.getMessage());
        return message.contains("HTTP 404") || message.contains("empty response");
    }

    private static final java.time.format.DateTimeFormatter RUN_DATE =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Builds one filter request, for a box that does not cross the
     * antimeridian. Package-private so it can be asserted in tests.
     */
    URI buildUri(BoundingBox bbox, GribSelection selection,
                 String yyyymmdd, int cycle, int forecastHour) {
        final GribModel model = selection.model();
        final StringBuilder q = new StringBuilder(base).append(model.filterScript()).append('?');

        q.append("file=").append(Http.encode(model.fileName(yyyymmdd, cycle, forecastHour)));

        // A LinkedHashSet because a variable and its pair can overlap with
        // another selection - asking for var_UGRD twice is not an error but it
        // is untidy, and the URL is asserted in a test.
        final java.util.Set<String> params = new java.util.LinkedHashSet<>();
        for (GribVariable v : selection.variables()) params.addAll(v.queryParams());
        for (String param : params) {
            q.append('&').append(param);
        }
        for (GribLevel l : selection.levels()) {
            q.append('&').append(l.queryParam());
        }

        // subregion is a flag with no value; the corners are only honoured with it.
        q.append("&subregion=")
         .append("&leftlon=").append(trim(bbox.west()))
         .append("&rightlon=").append(trim(bbox.east()))
         .append("&toplat=").append(trim(bbox.north()))
         .append("&bottomlat=").append(trim(bbox.south()));

        q.append("&dir=").append(Http.encode(model.directory(yyyymmdd, cycle)));

        return URI.create(q.toString());
    }

    private static String trim(double d) {
        final String s = String.format(java.util.Locale.ROOT, "%.4f", d);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }
}
