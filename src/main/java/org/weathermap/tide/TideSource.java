package org.weathermap.tide;

import java.io.IOException;
import java.time.LocalDate;

/**
 * Where tide predictions come from.
 *
 * <p>An interface for the same reason {@code GribSource} and {@code OsmSource}
 * are: the window and the chart should not know that the answer arrives as
 * JSON over HTTPS from one particular company, and a test should be able to
 * hand them an answer without a network.</p>
 */
public interface TideSource {

    /**
     * One prediction for one point.
     *
     * @param lat      degrees north
     * @param lon      degrees east, in -180..180
     * @param startDay the first UTC day to cover
     * @param days     how many days from {@code startDay} to ask for
     */
    TideData fetch(double lat, double lon, LocalDate startDay, int days)
            throws IOException, InterruptedException;

    /** Where this data comes from, for a status line. */
    String description();
}
