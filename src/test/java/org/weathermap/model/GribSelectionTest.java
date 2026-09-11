package org.weathermap.model;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
