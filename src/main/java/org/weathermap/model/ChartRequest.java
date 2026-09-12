package org.weathermap.model;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * One chart: which model run to take it from, and how far into that run.
 *
 * <p>Exists because a series is no longer one run sliced at several forecast
 * hours. A chart of last Tuesday afternoon cannot come from this morning's run
 * at all - forecast hours only count forwards - so each chart names its own
 * run, and a series spanning the past and the future draws from several.</p>
 *
 * @param runDate      the run's date, UTC
 * @param cycle        the run's hour: 0, 6, 12 or 18 for GFS
 * @param forecastHour hours from the run to the moment the chart depicts
 */
public record ChartRequest(LocalDate runDate, int cycle, int forecastHour)
        implements Comparable<ChartRequest> {

    public ChartRequest {
        if (forecastHour < 0) {
            throw new IllegalArgumentException(
                    "a forecast hour counts forwards from its run: " + forecastHour);
        }
    }

    /** When the run itself was made. */
    public Instant runTime() {
        return runDate.atStartOfDay(ZoneOffset.UTC).plusHours(cycle).toInstant();
    }

    /** The moment this chart depicts. */
    public Instant validTime() {
        return runTime().plus(Duration.ofHours(forecastHour));
    }

    /**
     * How stale the chart is, in hours.
     *
     * <p>Zero means an analysis - the model's own picture of the moment it
     * started from, which is the closest this source comes to what was actually
     * observed. It is the number that says how much to trust a chart of the
     * past: a 3-hour lead is nearly the analysis, a 72-hour lead is a forecast
     * that may since have been overtaken.</p>
     */
    public int leadHours() { return forecastHour; }

    /** True for the run's own analysis rather than a forecast from it. */
    public boolean isAnalysis() { return forecastHour == 0; }

    @Override
    public int compareTo(ChartRequest other) {
        return validTime().compareTo(other.validTime());
    }

    @Override
    public String toString() {
        return String.format("%s %02dZ f%03d", runDate, cycle, forecastHour);
    }
}
