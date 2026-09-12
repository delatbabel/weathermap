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
     * Downloads one file per request, each cut to {@code bbox}.
     *
     * <p>The requests carry their own runs rather than sharing the selection's,
     * because a series that reaches into the past draws from several: forecast
     * hours only count forwards, so a chart of yesterday afternoon cannot come
     * from this morning's run at all.</p>
     *
     * @return the downloaded files, in the order requested
     */
    List<Path> download(BoundingBox bbox, GribSelection selection,
                        List<ChartRequest> requests, Http.ProgressListener listener)
            throws IOException, InterruptedException;

    String description();
}
