package org.weathermap.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the user chose on the data side: which model, which run, which
 * forecast hours, and which variable/level pairs.
 *
 * <p>Paired with a {@link BoundingBox} this is the whole of a request, and the
 * pair is what {@link Preferences} persists so the CLI can repeat it. One
 * selection expands to one download per forecast hour, each cut to the box.</p>
 *
 * <p>Mutable and not thread-safe: it is edited by the UI on the EDT and then
 * handed to a worker as a {@link #copy()}.</p>
 */
public final class GribSelection {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * How long after a nominal run time NOMADS has usually finished publishing
     * it. Used by {@link #latestAvailableRun} so a CLI run started at 06:10Z
     * does not ask for the 06Z cycle that is still being written.
     */
    public static final Duration PUBLICATION_LAG = Duration.ofHours(4);

    private GribModel model = GribModel.GFS_0P25;

    /** {@code null} means "whatever the latest published run is at download time". */
    private LocalDate runDate;
    private int runCycle = -1;

    private final List<Integer> forecastHours = new ArrayList<>(List.of(0));
    private final Set<GribVariable> variables = new LinkedHashSet<>();
    private final Set<GribLevel> levels = new LinkedHashSet<>();

    public GribSelection() {
        variables.add(GribCatalog.TEMPERATURE);
        levels.add(GribCatalog.LEVEL_2M);
    }

    public GribModel model() { return model; }

    public void setModel(GribModel model) {
        this.model = model;
        // A model change can invalidate the run and the forecast hours; the
        // caller is expected to re-validate rather than have them silently
        // clamped, so a UI can explain what moved.
    }

    /** {@code null} when the selection follows the latest run. */
    public LocalDate runDate() { return runDate; }

    /** {@code -1} when the selection follows the latest run. */
    public int runCycle() { return runCycle; }

    public boolean followsLatestRun() { return runDate == null || runCycle < 0; }

    public void useLatestRun() {
        this.runDate = null;
        this.runCycle = -1;
    }

    public void setRun(LocalDate date, int cycle) {
        this.runDate = date;
        this.runCycle = cycle;
    }

    /**
     * Resolves {@link #followsLatestRun()} against the clock.
     *
     * @return {@code {yyyymmdd, cycle}} for the run to download
     */
    public Object[] resolveRun(ZonedDateTime now) {
        if (!followsLatestRun()) {
            return new Object[]{runDate.format(YYYYMMDD), runCycle};
        }
        final ZonedDateTime target = now.withZoneSameInstant(ZoneOffset.UTC).minus(PUBLICATION_LAG);
        final int[] cycles = model.cycleHours();
        int cycle = cycles[cycles.length - 1];
        LocalDate date = target.toLocalDate();
        for (int i = cycles.length - 1; i >= 0; i--) {
            if (cycles[i] <= target.getHour()) {
                cycle = cycles[i];
                return new Object[]{date.format(YYYYMMDD), cycle};
            }
        }
        // Before the day's first cycle: fall back to the last cycle of yesterday.
        return new Object[]{date.minusDays(1).format(YYYYMMDD), cycle};
    }

    /** Forecast hours to fetch, ascending. Never empty. */
    public List<Integer> forecastHours() { return List.copyOf(forecastHours); }

    public void setForecastHours(List<Integer> hours) {
        if (hours.isEmpty()) throw new IllegalArgumentException("at least one forecast hour");
        for (int h : hours) {
            if (h < 0 || h > model.maxForecastHour()) {
                throw new IllegalArgumentException(
                        "forecast hour " + h + " is outside 0.." + model.maxForecastHour()
                                + " for " + model.displayName());
            }
        }
        forecastHours.clear();
        forecastHours.addAll(hours);
        forecastHours.sort(Integer::compareTo);
    }

    public Set<GribVariable> variables() { return Set.copyOf(variables); }

    public void setVariables(Set<GribVariable> vars) {
        if (vars.isEmpty()) throw new IllegalArgumentException("at least one variable");
        variables.clear();
        variables.addAll(vars);
    }

    public Set<GribLevel> levels() { return Set.copyOf(levels); }

    public void setLevels(Set<GribLevel> levs) {
        if (levs.isEmpty()) throw new IllegalArgumentException("at least one level");
        levels.clear();
        levels.addAll(levs);
    }

    /** @return one composited PNG per forecast hour. */
    public int outputCount() { return forecastHours.size(); }

    public GribSelection copy() {
        final GribSelection c = new GribSelection();
        c.model = model;
        c.runDate = runDate;
        c.runCycle = runCycle;
        c.setForecastHours(forecastHours());
        c.setVariables(variables());
        c.setLevels(levels());
        return c;
    }

    @Override
    public String toString() {
        return model.displayName() + " "
                + (followsLatestRun() ? "latest run" : runDate + " " + runCycle + "Z")
                + " f" + forecastHours
                + " " + variables + " @ " + levels;
    }
}
