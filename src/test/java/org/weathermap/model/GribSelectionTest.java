package org.weathermap.model;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GribSelectionTest {

    @Test
    void latestRunAllowsForPublicationLag() {
        final GribSelection sel = new GribSelection();
        sel.useLatestRun();

        // 07:30Z minus the four-hour lag is 03:30, so the 00Z run is the newest
        // one that is certainly finished - not the 06Z one still being written.
        assertArrayEquals(new Object[]{"20260911", 0},
                sel.resolveRun(ZonedDateTime.parse("2026-09-11T07:30:00Z")));
    }

    @Test
    void latestRunFallsBackToYesterdayBeforeTheFirstCycle() {
        final GribSelection sel = new GribSelection();
        sel.useLatestRun();
        assertArrayEquals(new Object[]{"20260910", 18},
                sel.resolveRun(ZonedDateTime.parse("2026-09-11T02:00:00Z")));
    }

    @Test
    void forecastHoursAreSortedAndRangeChecked() {
        final GribSelection sel = new GribSelection();
        sel.setForecastHours(java.util.List.of(12, 0, 6));
        assertEquals(java.util.List.of(0, 6, 12), sel.forecastHours());

        assertThrows(IllegalArgumentException.class,
                () -> sel.setForecastHours(java.util.List.of(999)));
    }

    @Test
    void copyIsIndependent() {
        final GribSelection original = new GribSelection();
        final GribSelection copy = original.copy();
        copy.setForecastHours(java.util.List.of(24));
        assertEquals(java.util.List.of(0), original.forecastHours());
    }

    /**
     * The failure this whole check exists for: the chart came back with no wind
     * on it at all, and the only clue was "matched no GRIB records".
     */
    @Test
    void windAtTwoMetresIsReportedAsImpossible() {
        final GribSelection sel = new GribSelection();
        sel.setVariables(java.util.Set.of(GribCatalog.WIND, GribCatalog.PRECIPITATION));
        sel.setLevels(java.util.Set.of(GribCatalog.LEVEL_2M));

        final java.util.List<String> problems = sel.problems();
        assertEquals(2, problems.size(), problems.toString());
        // The message has to name the fix, not just the fault.
        assertTrue(problems.stream().anyMatch(m -> m.contains("10 m above ground")),
                   problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("Surface")),
                   problems.toString());
    }

    @Test
    void missingLevelsAreAddedWithoutRemovingTheChosenOnes() {
        final GribSelection sel = new GribSelection();
        sel.setVariables(java.util.Set.of(GribCatalog.WIND, GribCatalog.TEMPERATURE));
        sel.setLevels(java.util.Set.of(GribCatalog.LEVEL_2M));

        final java.util.List<GribLevel> added = sel.addMissingLevels();

        assertEquals(java.util.List.of(GribCatalog.LEVEL_10M), added);
        // Temperature was already served by 2 m, so nothing was added for it,
        // and the level the user picked survives.
        assertTrue(sel.levels().contains(GribCatalog.LEVEL_2M));
        assertTrue(sel.levels().contains(GribCatalog.LEVEL_10M));
        assertEquals(java.util.List.of(), sel.problems());
    }

    @Test
    void aWorkableSelectionIsLeftAlone() {
        final GribSelection sel = new GribSelection();
        sel.setVariables(java.util.Set.of(GribCatalog.WIND, GribCatalog.TEMPERATURE));
        sel.setLevels(java.util.Set.of(GribCatalog.LEVEL_10M, GribCatalog.LEVEL_2M));

        assertEquals(java.util.List.of(), sel.problems());
        assertEquals(java.util.List.of(), sel.addMissingLevels());
    }

    /**
     * The catalogue knows the common levels, not every level every model
     * publishes, so a pairing it has never heard of has to be allowed through.
     */
    @Test
    void anUnknownVariableIsNeverBlocked() {
        final GribSelection sel = new GribSelection();
        sel.setVariables(java.util.Set.of(GribCatalog.variable("GUST")));
        sel.setLevels(java.util.Set.of(GribCatalog.LEVEL_2M));

        assertEquals(java.util.List.of(), sel.problems());
        assertEquals(java.util.List.of(), sel.addMissingLevels());
    }

    // ---- a series of charts ---------------------------------------------

    /**
     * The subtlety the whole feature turns on: forecast hours are counted from
     * the model run, and the run in hand is always hours old. "Starting now" is
     * therefore not hour 0 - hour 0 renders perfectly well and is a chart of
     * this morning, which is exactly the kind of mistake nobody spots.
     */
    @Test
    void aSeriesStartsAtNowNotAtHourZero() {
        final GribSelection sel = new GribSelection();
        sel.useLatestRun();
        sel.setSeries(3, 12);

        // 09:30Z minus the four-hour publication lag lands in the 00Z run, so
        // the run is 9.5 hours old and the first three-hourly step after that
        // is hour 12.
        final java.util.List<Integer> hours =
                sel.seriesHours(ZonedDateTime.parse("2026-09-12T09:30:00Z"));

        assertEquals(java.util.List.of(12, 15, 18, 21), hours);
    }

    @Test
    void theSpanIsMeasuredFromNowNotFromTheRun() {
        final GribSelection sel = new GribSelection();
        sel.useLatestRun();
        sel.setSeries(6, 48);

        final java.util.List<Integer> hours =
                sel.seriesHours(ZonedDateTime.parse("2026-09-12T09:30:00Z"));

        // First step at or after 9.5 hours, then out to 9.5 + 48 = 57.5.
        assertEquals(12, hours.get(0));
        assertEquals(54, hours.get(hours.size() - 1));
    }

    @Test
    void theDefaultsAreThreeHourlyForTwoDays() {
        final GribSelection sel = new GribSelection();
        sel.useLatestRun();
        sel.setSeries(GribSelection.DEFAULT_SERIES_STEP_HOURS,
                      GribSelection.DEFAULT_SERIES_SPAN_HOURS);

        final java.util.List<Integer> hours =
                sel.seriesHours(ZonedDateTime.parse("2026-09-12T09:30:00Z"));

        // 9.5 hours old at 09:30Z, so hours 12 through 57 - sixteen charts.
        assertEquals(16, hours.size(), hours.toString());
        for (int i = 1; i < hours.size(); i++) {
            assertEquals(3, hours.get(i) - hours.get(i - 1));
        }
    }

    /** A series must not run past the end of the model. */
    @Test
    void aSeriesStopsAtTheModelsLastHour() {
        final GribSelection sel = new GribSelection();
        sel.useLatestRun();
        sel.setSeries(6, 1000);

        final java.util.List<Integer> hours =
                sel.seriesHours(ZonedDateTime.parse("2026-09-12T09:30:00Z"));

        assertTrue(hours.get(hours.size() - 1) <= sel.model().maxForecastHour(),
                   hours.get(hours.size() - 1) + " is beyond the model");
        assertFalse(hours.isEmpty());
    }

    @Test
    void anImpossibleSeriesIsRefusedRatherThanSilentlyEmpty() {
        final GribSelection sel = new GribSelection();
        assertThrows(IllegalArgumentException.class, () -> sel.setSeries(0, 48));
        assertThrows(IllegalArgumentException.class, () -> sel.setSeries(-3, 48));
        assertThrows(IllegalArgumentException.class, () -> sel.setSeries(6, 3));
    }

    /**
     * A series is a standing description, not the hours it happened to resolve
     * to - that is what lets an unattended run mean the same thing tomorrow.
     */
    @Test
    void applyingASeriesFillsInTheHoursAndKeepsTheDefinition() {
        final GribSelection sel = new GribSelection();
        sel.useLatestRun();
        sel.setSeries(3, 12);
        sel.applySeries(ZonedDateTime.parse("2026-09-12T09:30:00Z"));

        assertEquals(java.util.List.of(12, 15, 18, 21), sel.forecastHours());
        assertTrue(sel.hasSeries());
        assertEquals(3, sel.seriesStepHours());
        assertEquals(12, sel.seriesSpanHours());
    }

    /**
     * Every part of the series, not just the parts that existed when this test
     * was written. The desktop application runs on a copy, so anything copy()
     * forgets is a feature that works from the command line and silently does
     * not work in the window - which is how the backward leg was lost.
     */
    @Test
    void aSeriesSurvivesBeingCopied() {
        final GribSelection sel = new GribSelection();
        sel.setSeries(6, 24, 72);
        final GribSelection copy = sel.copy();

        assertTrue(copy.hasSeries());
        assertEquals(6, copy.seriesStepHours());
        assertEquals(24, copy.seriesHoursBack());
        assertEquals(72, copy.seriesSpanHours());
        assertEquals(sel.toString(), copy.toString());
    }

    /** A copy asked for the same charts as the original, which is the real test. */
    @Test
    void aCopyResolvesToTheSameChartsAsTheOriginal() {
        final GribSelection sel = new GribSelection();
        sel.useLatestRun();
        sel.setSeries(6, 48, 48);

        final ZonedDateTime now = ZonedDateTime.parse("2026-09-12T15:24:00Z");
        assertEquals(sel.chartRequests(now), sel.copy().chartRequests(now));
        assertEquals(16, sel.chartRequests(now).size());
    }

    @Test
    void withNoSeriesTheListedHoursAreLeftAlone() {
        final GribSelection sel = new GribSelection();
        sel.setForecastHours(java.util.List.of(0, 6, 12));
        sel.applySeries(ZonedDateTime.parse("2026-09-12T09:30:00Z"));

        assertEquals(java.util.List.of(0, 6, 12), sel.forecastHours());
    }
}
