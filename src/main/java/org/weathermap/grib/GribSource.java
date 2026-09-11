package org.weathermap.grib;

import org.weathermap.model.BoundingBox;
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
     * Downloads one file per forecast hour in {@code selection}, each cut to
     * {@code bbox}.
     *
     * @return the downloaded files, in forecast-hour order
     */
    List<Path> download(BoundingBox bbox, GribSelection selection,
                        Http.ProgressListener listener)
            throws IOException, InterruptedException;

    String description();
}
