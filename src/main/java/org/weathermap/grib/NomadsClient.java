package org.weathermap.grib;

import org.weathermap.model.BoundingBox;
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
    public List<Path> download(BoundingBox bbox, GribSelection selection,
                               Http.ProgressListener listener)
            throws IOException, InterruptedException {

        final Object[] run = selection.resolveRun(ZonedDateTime.now());
        final String yyyymmdd = (String) run[0];
        final int cycle = (Integer) run[1];

        final List<Path> out = new ArrayList<>();
        for (int forecastHour : selection.forecastHours()) {
            if (listener != null && listener.isCancelled()) break;

            final URI uri = buildUri(bbox, selection, yyyymmdd, cycle, forecastHour);

            // A published run is immutable, so a cache hit needs no expiry check
            // beyond "is it there and non-empty".
            final Path entry = cache.pathFor("grib", uri.toString(), ".grib2");
            if (cache.isFresh(entry, null)) {
                LOG.fine(() -> "GRIB cache hit: " + entry);
            }
            else {
                LOG.info(() -> "Downloading " + selection.model().displayName()
                        + " " + yyyymmdd + " " + cycle + "Z f" + forecastHour);
                Http.download(uri, entry, listener);
            }
            out.add(entry);
        }
        return out;
    }

    /** Builds one filter request. Package-private so it can be asserted in tests. */
    URI buildUri(BoundingBox bbox, GribSelection selection,
                 String yyyymmdd, int cycle, int forecastHour) {
        final GribModel model = selection.model();
        final StringBuilder q = new StringBuilder(base).append(model.filterScript()).append('?');

        q.append("file=").append(Http.encode(model.fileName(yyyymmdd, cycle, forecastHour)));

        for (GribVariable v : selection.variables()) {
            q.append('&').append(v.queryParam());
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
