package org.weathermap.tide;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TideChartTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final TidePoint VUNG_TAU = new TidePoint("Vung Tau", 10.34, 107.08);

    /** A semidiurnal day: hourly samples, and the turning points between them. */
    private static TideData sample() {
        final Instant start = DAY.minusDays(1).atStartOfDay(ZONE).toInstant();
        final List<TideData.Reading> readings = new ArrayList<>();
        for (int h = 0; h <= 72; h++) {
            readings.add(new TideData.Reading(start.plus(Duration.ofHours(h)), height(h)));
        }
        // Two highs and two lows on the day itself, at times that are not on
        // the hour - which is the case the chart has to get right.
        final List<TideData.Extreme> extremes = List.of(
                extreme(start, 24 + 0.37, 1.25, TideData.Extreme.Kind.HIGH),
                extreme(start, 24 + 6.43, -0.88, TideData.Extreme.Kind.LOW),
                extreme(start, 24 + 12.43, 1.19, TideData.Extreme.Kind.HIGH),
                extreme(start, 24 + 18.82, -1.32, TideData.Extreme.Kind.LOW));

        return new TideData(new TideData.Station("Vung Tau", "sg", 12, 10.34, 107.08),
                            "MSL", readings, extremes,
                            new TideData.Quota(4, 10), Instant.now(), false);
    }

    private static double height(double hours) {
        return 0.05 + 1.15 * Math.sin(2 * Math.PI * hours / 12.4206 + 1.9);
    }

    private static TideData.Extreme extreme(Instant start, double hours, double metres,
                                            TideData.Extreme.Kind kind) {
        return new TideData.Extreme(
                start.plus(Duration.ofSeconds(Math.round(hours * 3600))), metres, kind);
    }

    // ---- the shape of the picture -----------------------------------------

    @Test
    void takesItsWidthFromThePreferencesAndDerivesTheHeight() {
        // A tide chart is wide and short whatever shape the map is set to.
        assertEquals(1600, TideChart.fitSize(1600, 1200)[0]);
        assertEquals(900, TideChart.fitSize(1600, 1200)[1]);
        assertEquals(16.0 / 9, (double) TideChart.fitSize(1600, 1200)[0]
                / TideChart.fitSize(1600, 1200)[1], 0.01);
    }

    @Test
    void neverAsksForAnImageTooSmallToRead() {
        assertTrue(TideChart.fitSize(10, 10)[0] >= 480);
        assertTrue(TideChart.fitSize(10, 10)[1] >= 270);
    }

    @Test
    void aShortOutputHeightIsHonouredByNarrowingInstead() {
        final int[] size = TideChart.fitSize(1600, 400);
        assertEquals(400, size[1]);
        assertTrue(size[0] < 1600, "narrowed to keep the shape, not stretched");
    }

    @Test
    void choosesAGridStepThatLeavesAReadableNumberOfLines() {
        assertEquals(0.5, TideChart.heightStep(3.0), 1e-9);
        assertEquals(0.05, TideChart.heightStep(0.3), 1e-9);
        assertEquals(2, TideChart.heightStep(12), 1e-9);
    }

    // ---- the curve ----------------------------------------------------------

    /**
     * The whole reason the two responses are merged. Hourly samples alone put
     * every high water on the hour and cut the top off it, so the line would
     * disagree with the figures printed under it.
     */
    @Test
    void theCurvePassesThroughTheRealTurningPoints() {
        final TideData data = sample();
        final List<TideData.Reading> curve = TideChart.curveThrough(data, DAY, ZONE);

        for (TideData.Extreme e : data.extremesFor(DAY, ZONE)) {
            assertTrue(curve.stream().anyMatch(
                            r -> r.time().equals(e.time()) && r.metres() == e.metres()),
                    "the curve misses the " + e.kind() + " at " + e.time());
        }
    }

    @Test
    void theCurveStaysInTimeOrder() {
        final List<TideData.Reading> curve = TideChart.curveThrough(sample(), DAY, ZONE);
        for (int i = 1; i < curve.size(); i++) {
            assertFalse(curve.get(i).time().isBefore(curve.get(i - 1).time()),
                        "out of order at " + i);
        }
    }

    /**
     * The readings either side of midnight are included, or the line starts
     * and stops short of the edges with the water plainly still there.
     */
    @Test
    void theCurveReachesBothEdgesOfTheDay() {
        final List<TideData.Reading> curve = TideChart.curveThrough(sample(), DAY, ZONE);
        final Instant from = DAY.atStartOfDay(ZONE).toInstant();
        final Instant to = DAY.plusDays(1).atStartOfDay(ZONE).toInstant();

        assertFalse(curve.get(0).time().isAfter(from), "nothing before midnight");
        assertFalse(curve.get(curve.size() - 1).time().isBefore(to), "nothing after it");
    }

    // ---- what gets drawn -----------------------------------------------------

    private static boolean contains(BufferedImage image, java.awt.Color colour) {
        final int rgb = colour.getRGB();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) == rgb) return true;
            }
        }
        return false;
    }

    @Test
    void drawsTheWaterAndMarksThePeaks() {
        final BufferedImage image =
                TideChart.render(sample(), DAY, ZONE, VUNG_TAU, 960, 540);

        assertEquals(960, image.getWidth());
        assertEquals(540, image.getHeight());
        assertTrue(contains(image, new java.awt.Color(207, 227, 243)), "the water under the curve");
        assertTrue(contains(image, new java.awt.Color(30, 96, 150)), "a high water mark");
        assertTrue(contains(image, new java.awt.Color(120, 152, 180)), "a low water mark");
    }

    /**
     * The chart is a document: it keeps its own colours whatever theme the
     * window is wearing, so that a chart saved at night matches one saved by
     * day. The background is white, always.
     */
    @Test
    void theChartIsALightDocumentWhateverTheWindowIsDoing() {
        final BufferedImage image =
                TideChart.render(sample(), DAY, ZONE, VUNG_TAU, 960, 540);
        assertEquals(java.awt.Color.WHITE.getRGB(), image.getRGB(2, 2));
        assertEquals(java.awt.Color.WHITE.getRGB(),
                     image.getRGB(image.getWidth() - 2, image.getHeight() - 2));
    }

    @Test
    void aDayWithNoDataDrawsAChartThatSaysSo() {
        final TideData empty = new TideData(
                new TideData.Station("Nowhere", "sg", 5, 0, 0), "MSL",
                List.of(), List.of(), TideData.Quota.UNKNOWN, Instant.now(), false);

        final BufferedImage image = TideChart.render(
                empty, DAY, ZONE, new TidePoint("Nowhere", 0, 0), 960, 540);
        assertEquals(960, image.getWidth());
        assertFalse(contains(image, new java.awt.Color(207, 227, 243)), "no water drawn");
    }

    @Test
    void aDayOutsideTheFetchedRangeIsNotAnError() {
        final BufferedImage image =
                TideChart.render(sample(), DAY.plusYears(1), ZONE, VUNG_TAU, 960, 540);
        assertEquals(540, image.getHeight());
    }
}
