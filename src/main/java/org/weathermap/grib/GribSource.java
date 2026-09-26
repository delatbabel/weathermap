package org.weathermap.grib;

import org.weathermap.model.BoundingBox;
import org.weathermap.model.ChartRequest;
import org.weathermap.model.GribSelection;
import org.weathermap.util.Http;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Where GRIB files come from.
 *
 * <p>Separate from {@link GribReader} because downloading and decoding are
 * independent concerns: a file already on disk is read the same way whether it
 * came from NOMADS, from a colleague, or from a previous run's cache.</p>
 */
public interface GribSource {

    /**
     * One chart that was actually fetched, as one file or as several.
     *
     * <p>Several when the area crosses the antimeridian: no filter service will
     * cut a subregion whose {@code leftlon} is east of its {@code rightlon}, so
     * the box is fetched as one piece per side of the seam. The parts are in
     * west-to-east order, and it is the caller's job to join the fields they
     * decode to - see {@link Grid#join}.</p>
     */
    record Downloaded(ChartRequest request, List<Path> files) {

        public Downloaded {
            if (files.isEmpty()) throw new IllegalArgumentException("no file for " + request);
            files = List.copyOf(files);
        }

        /**
         * One part, for a source that never splits.
         *
         * <p>There is deliberately no accessor for "the file": reaching for one
         * part of a chart that has two is how a Pacific chart would quietly
         * come out as half an ocean, with nothing to say so.</p>
         */
        public Downloaded(ChartRequest request, Path file) {
            this(request, List.of(file));
        }
    }

    /**
     * Downloads one file per request, each cut to {@code bbox}.
     *
     * <p>The requests carry their own runs rather than sharing the selection's,
     * because a series that reaches into the past draws from several: forecast
     * hours only count forwards, so a chart of yesterday afternoon cannot come
     * from this morning's run at all.</p>
     *
     * <p>The result pairs each file with the request that produced it rather
     * than relying on position, because it may be <em>shorter</em> than the
     * requests: a run old enough to have rolled off the archive is skipped, and
     * a series of a week ago that failed entirely because its first chart had
     * aged out would be a poor trade for a run that is nearly all there.</p>
     *
     * @return what was fetched, in the order requested; possibly fewer than asked
     */
    List<Downloaded> download(BoundingBox bbox, GribSelection selection,
                              List<ChartRequest> requests, Http.ProgressListener listener)
            throws IOException, InterruptedException;

    String description();
}
