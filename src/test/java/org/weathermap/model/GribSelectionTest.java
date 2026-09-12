package org.weathermap.model;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
