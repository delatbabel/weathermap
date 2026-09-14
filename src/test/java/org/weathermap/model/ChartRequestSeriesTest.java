package org.weathermap.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A series that reaches into the past draws on several runs, because forecast
 * hours only count forwards: a chart of yesterday afternoon cannot come from
 * this morning's run at all.
 *
 * <p>One rule covers both directions — take the latest cycle at or before
 * {@code min(valid time, now - publication lag)} — and these pin what it
 * produces at each end.</p>
 */
class ChartRequestSeriesTest {

    /** 09:30Z, so the 00Z run is the newest that has certainly been published. */
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-09-12T09:30:00Z");

    private static GribSelection series(int step, int back, int ahead) {
        final GribSelection sel = new GribSelection();
        sel.useLatestRun();
        sel.setSeries(step, back, ahead);
        return sel;
    }

    @Test
    void aForwardOnlySeriesUsesTheOneNewestRun() {
        final List<ChartRequest> requests = series(3, 0, 12).chartRequests(NOW);

        assertFalse(requests.isEmpty());
        for (ChartRequest r : requests) {
            assertEquals(0, r.cycle(), r.toString());
            assertEquals("2026-09-12", r.runDate().toString());
        }
    }

    /** GFS runs every six hours, so a run is never more than six hours old. */
    private static final int CYCLE_INTERVAL_HOURS = 6;

    /**
     * The point of the whole feature: a chart of the past comes from the run of
     * its own time, so its lead is short rather than growing with age.
     *
     * <p>Once a moment is older than the publication lag, its own run is
     * certainly out, and the lead is therefore under one cycle interval. Nearer
     * to now the newest run is still publishing, so the lead can reach a cycle
     * interval plus the lag - that is the data's limit, not the rule's.</p>
     */
    @Test
    void pastChartsComeFromTheRunsOfTheirOwnTime() {
        final List<ChartRequest> requests = series(3, 24, 0).chartRequests(NOW);
        assertFalse(requests.isEmpty());

        final Instant settled = NOW.minus(GribSelection.PUBLICATION_LAG).toInstant();
        for (ChartRequest r : requests) {
            if (!r.validTime().isBefore(NOW.toInstant())) continue;

            final int limit = r.validTime().isBefore(settled)
                    ? CYCLE_INTERVAL_HOURS
                    : CYCLE_INTERVAL_HOURS + (int) GribSelection.PUBLICATION_LAG.toHours();
            assertTrue(r.leadHours() <= limit,
                       r + " is a " + r.leadHours() + "-hour forecast of the past");
        }
        // Half of them land on a cycle, so they are analyses.
        assertTrue(requests.stream().anyMatch(ChartRequest::isAnalysis),
                   "a three-hourly series should hit the 6-hourly cycles");
    }

    /** The further back a chart is, the closer it should be to an analysis. */
    @Test
    void aChartWellInThePastIsNeverAStaleForecast() {
        final List<ChartRequest> requests = series(3, 48, 0).chartRequests(NOW);

        final Instant settled = NOW.minus(GribSelection.PUBLICATION_LAG).toInstant();
        final List<ChartRequest> old = requests.stream()
                .filter(r -> r.validTime().isBefore(settled))
                .toList();

        assertFalse(old.isEmpty());
        for (ChartRequest r : old) {
            assertTrue(r.leadHours() < CYCLE_INTERVAL_HOURS,
                       r + " should have come from a nearer run");
        }
    }

    @Test
    void aSeriesSpanningNowCoversBothSidesOfIt() {
        final List<ChartRequest> requests = series(6, 24, 24).chartRequests(NOW);

        final Instant now = NOW.toInstant();
        assertTrue(requests.stream().anyMatch(r -> r.validTime().isBefore(now)));
        assertTrue(requests.stream().anyMatch(r -> r.validTime().isAfter(now)));

        // In time order, and every step is one interval.
        for (int i = 1; i < requests.size(); i++) {
            assertEquals(6 * 3600,
                         requests.get(i).validTime().getEpochSecond()
                                 - requests.get(i - 1).validTime().getEpochSecond());
        }
    }

    /**
     * A run exists on paper before NOMADS has finished publishing it. Asking for
     * one that is not there yet fails with a 404 rather than falling back, so
     * the rule clamps to what the lag says is available.
     */
    @Test
    void noChartIsTakenFromARunThatIsNotPublishedYet() {
        final List<ChartRequest> requests = series(3, 6, 12).chartRequests(NOW);

        final Instant newestUsable = NOW.minus(GribSelection.PUBLICATION_LAG).toInstant();
        for (ChartRequest r : requests) {
            assertFalse(r.runTime().isAfter(newestUsable),
                        r + " uses a run that may still be publishing");
        }
    }

    /** Valid times sit on the step grid, so two runs minutes apart agree. */
    @Test
    void chartTimesAreAlignedToTheStepNotToTheClock() {
        final List<ChartRequest> at0930 = series(3, 12, 12).chartRequests(NOW);
        final List<ChartRequest> at0947 = series(3, 12, 12)
                .chartRequests(ZonedDateTime.parse("2026-09-12T09:47:00Z"));

        assertEquals(at0930.get(0).validTime(), at0947.get(0).validTime());
        for (ChartRequest r : at0930) {
            assertEquals(0, r.validTime().getEpochSecond() % (3 * 3600), r.toString());
        }
    }

    @Test
    void withoutASeriesTheListedHoursAreUsedAgainstOneRun() {
        final GribSelection sel = new GribSelection();
        sel.useLatestRun();
        sel.setForecastHours(List.of(0, 6, 12));

        final List<ChartRequest> requests = sel.chartRequests(NOW);

        assertEquals(List.of(0, 6, 12), requests.stream().map(ChartRequest::forecastHour).toList());
        assertEquals(1, requests.stream().map(ChartRequest::runTime).distinct().count());
    }

    /**
     * Three days back was asked for and refused by a limit that was a guess, not
     * a fact. The archive holds nine to ten days, so the cap now sits at eight.
     */
    @Test
    void aSeriesCanReachSeveralDaysBack() {
        final List<ChartRequest> requests = series(6, 72, 0).chartRequests(NOW);

        assertEquals(Instant.parse("2026-09-09T12:00:00Z"), requests.get(0).validTime());
        assertTrue(requests.size() >= 12, "72 hours at 6-hourly steps: " + requests.size());
        assertTrue(GribSelection.MAX_SERIES_HOURS_BACK >= 72);
    }

    /**
     * The cap on the past stands; the floor at zero does not.
     *
     * <p>A negative reach back used to be refused outright. It now means a
     * series that starts that many hours in the future, which is what a chart
     * posted in the evening about tomorrow needs - see
     * {@link #aSeriesCanStartAheadOfTheClock}. The invariant that remains is
     * that the window cannot end before it begins.</p>
     */
    @Test
    void theReachIntoThePastIsCapped() {
        final GribSelection sel = new GribSelection();
        assertThrows(IllegalArgumentException.class,
                     () -> sel.setSeries(3, GribSelection.MAX_SERIES_HOURS_BACK + 1, 12));

        sel.setSeries(3, -6, 12);
        assertEquals(-6, sel.seriesHoursBack(), "a negative reach starts the series ahead");
    }

    @Test
    void aForecastHourCannotBeNegative() {
        assertThrows(IllegalArgumentException.class,
                     () -> new ChartRequest(java.time.LocalDate.of(2026, 9, 12), 0, -3));
    }

    @Test
    void aRequestKnowsWhenItIsFor() {
        final ChartRequest request = new ChartRequest(java.time.LocalDate.of(2026, 9, 12), 6, 9);

        assertEquals(Instant.parse("2026-09-12T06:00:00Z"), request.runTime());
        assertEquals(Instant.parse("2026-09-12T15:00:00Z"), request.validTime());
        assertEquals(9, request.leadHours());
        assertFalse(request.isAnalysis());
        assertTrue(new ChartRequest(java.time.LocalDate.of(2026, 9, 12), 6, 0).isAnalysis());
    }

    // ---- a series that starts later than now -------------------------------

    private static java.time.ZonedDateTime bangkok(int hour, int minute, int second) {
        return java.time.ZonedDateTime.of(2026, 9, 14, hour, minute, second, 0,
                                          java.time.ZoneId.of("Asia/Bangkok"));
    }

    private static java.time.LocalDateTime firstChartLocal(GribSelection selection,
                                                           java.time.ZonedDateTime now) {
        return selection.chartRequests(now).get(0).validTime()
                .atZone(now.getZone()).toLocalDateTime();
    }

    /**
     * The evening run, and the second that decides what it is a chart of.
     *
     * <p>A six-hourly series is aligned to the UTC grid, so in Bangkok the
     * charts fall at 01:00, 07:00, 13:00 and 19:00 local. Started in the
     * evening the first one is therefore 01:00 tomorrow, which is the whole
     * reason for posting then - but only just. At 19:00:00 exactly the window
     * still begins on the 19:00 chart, which is today; a second later it does
     * not.</p>
     *
     * <p>That is a knife edge for anything scheduled at 19:00, where whether
     * the post is about today or tomorrow comes down to how long the machine
     * took to start. A few minutes past the hour, or {@code --start}, settles
     * it.</p>
     */
    @Test
    void anEveningSeriesBeginsWithTomorrowButOnlyJustAfterSeven() {
        final GribSelection selection = new GribSelection();
        selection.setSeries(6, 0, 48);

        assertEquals(java.time.LocalDateTime.of(2026, 9, 14, 19, 0),
                firstChartLocal(selection, bangkok(19, 0, 0)),
                "at exactly 19:00 the first chart is still this evening");
        assertEquals(java.time.LocalDateTime.of(2026, 9, 15, 1, 0),
                firstChartLocal(selection, bangkok(19, 0, 1)),
                "one second later it is tomorrow");
        assertEquals(java.time.LocalDateTime.of(2026, 9, 15, 1, 0),
                firstChartLocal(selection, bangkok(19, 5, 0)),
                "which is why a schedule should not sit on the hour");
    }

    /**
     * A negative {@code hoursBack} starts the series in the future.
     *
     * <p>Without it a series can only begin at "now", and a chart posted in the
     * evening that is meant to be about tomorrow begins with one of this
     * evening. Only the window moves - which run each chart is drawn from is
     * still decided against the real clock.</p>
     */
    @Test
    void aSeriesCanStartAheadOfTheClock() {
        final GribSelection selection = new GribSelection();
        selection.setSeries(3, -12, 12 + 24);      // from 12 hours ahead, for 24 hours

        final java.time.ZonedDateTime now = bangkok(19, 30, 0);
        final var requests = selection.chartRequests(now);

        final java.time.LocalDateTime first = firstChartLocal(selection, now);
        assertTrue(first.isAfter(now.toLocalDateTime().plusHours(11)),
                "the series should begin about twelve hours out, not now: " + first);

        // At or after the moment asked for, never before it: 19:30 +07 is
        // 12:30Z, twelve hours on is 00:30Z, and the next three-hourly slot on
        // the UTC grid is 03:00Z - 10:00 in Bangkok. The grid is what makes two
        // runs twenty minutes apart name the same charts, so it wins.
        assertEquals(java.time.LocalDateTime.of(2026, 9, 15, 10, 0), first);

        // Still drawn from runs that have actually been published.
        for (ChartRequest request : requests) {
            assertTrue(request.leadHours() >= 0, "lead " + request.leadHours());
        }
    }

    @Test
    void aWindowThatStartsAfterItEndsIsRefused() {
        final GribSelection selection = new GribSelection();
        assertThrows(IllegalArgumentException.class, () -> selection.setSeries(3, -48, 12));
    }
}
