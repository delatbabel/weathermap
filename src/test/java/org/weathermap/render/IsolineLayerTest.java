package org.weathermap.render;

import org.junit.jupiter.api.Test;
import org.weathermap.grib.Grid;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribCatalog;
import org.weathermap.model.GribLevel;
import org.weathermap.model.GribVariable;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contour interval is a convention, not a preference: four hectopascals is
 * what marine and synoptic charts use, so the spacing of the lines means the
 * same here as on a chart from anywhere else.
 */
class IsolineLayerTest {

    private static Grid grid(GribVariable variable, GribLevel level, float low, float high) {
        return new Grid(variable, level, Instant.parse("2026-09-12T06:00:00Z"),
                        BoundingBox.of(49, -3, 52, 3), 2, 1, new float[]{low, high});
    }

    @Test
    void pressureIsContouredEveryFourHectopascals() {
        // GRIB carries pascals, charts are written in hectopascals.
        final IsolineLayer layer = new IsolineLayer(
                grid(GribCatalog.PRESSURE_MSL, GribCatalog.LEVEL_MSL, 99_000f, 103_000f));

        assertEquals(4.0, layer.labelledInterval(), 1e-9);
        assertEquals("hPa", layer.labelUnit());
    }

    @Test
    void geopotentialHeightIsContouredEverySixtyMetres() {
        final IsolineLayer layer = new IsolineLayer(
                grid(GribCatalog.GEOPOTENTIAL_HEIGHT, GribCatalog.LEVEL_500MB, 5000f, 5900f));

        assertEquals(60.0, layer.labelledInterval(), 1e-9);
        assertEquals("gpm", layer.labelUnit());
    }

    /**
     * A variable with no convention still has to produce a readable number of
     * lines rather than one every 0.0037 of a unit.
     */
    @Test
    void anythingElseGetsARoundIntervalAcrossItsRange() {
        assertEquals(10.0, new IsolineLayer(
                grid(GribCatalog.RELATIVE_HUMIDITY, GribCatalog.LEVEL_2M, 0f, 100f))
                .labelledInterval(), 1e-9);

        assertEquals(0.5, new IsolineLayer(
                grid(GribCatalog.variable("SOMETHING"), GribCatalog.LEVEL_SURFACE, 0f, 5f))
                .labelledInterval(), 1e-9);
    }

    /** A flat field has no range to divide; it must not divide by zero. */
    @Test
    void aConstantFieldDoesNotProduceAnImpossibleInterval() {
        final IsolineLayer layer = new IsolineLayer(
                grid(GribCatalog.variable("FLAT"), GribCatalog.LEVEL_SURFACE, 7f, 7f));
        assertEquals(1.0, layer.labelledInterval(), 1e-9);
    }

    // ---- pressure centres ------------------------------------------------

    private static final int COLS = 130;
    private static final int ROWS = 90;
    private static final int STEP = 4;

    /** Half a contour interval, as the layer itself uses for pressure. */
    private static final double PROMINENCE = 200;

    private static float[] flat(float base) {
        final float[] field = new float[COLS * ROWS];
        java.util.Arrays.fill(field, base);
        return field;
    }

    private static void bump(float[] field, double col, double row,
                             double amplitude, double sigma) {
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                final double d2 = (x - col) * (x - col) + (y - row) * (y - row);
                field[y * COLS + x] +=
                        (float) (amplitude * Math.exp(-d2 / (2 * sigma * sigma)));
            }
        }
    }

    @Test
    void aHighAndALowAreFoundAndToldApart() {
        final float[] field = flat(101_000);
        bump(field, 30, 45, 1600, 10);
        bump(field, 100, 45, -1600, 10);

        final List<IsolineLayer.Centre> centres =
                IsolineLayer.centresOf(field, COLS, ROWS, STEP, PROMINENCE);

        assertEquals(2, centres.size(), centres.toString());
        final IsolineLayer.Centre high = centres.stream().filter(IsolineLayer.Centre::high)
                .findFirst().orElseThrow();
        final IsolineLayer.Centre low = centres.stream().filter(c -> !c.high())
                .findFirst().orElseThrow();

        assertEquals(30 * STEP, high.x(), 3 * STEP);
        assertEquals(100 * STEP, low.x(), 3 * STEP);
        assertTrue(high.value() > low.value());
    }

    /**
     * A field still rising as it leaves the chart has its maximum on the border.
     * That is not a high - it is a high somewhere off the map, and marking the
     * edge of the paper claims to know where it is.
     */
    @Test
    void aMaximumAgainstTheEdgeIsNotACentre() {
        final float[] field = flat(101_000);
        bump(field, 2, 45, 1600, 10);

        assertEquals(List.of(),
                     IsolineLayer.centresOf(field, COLS, ROWS, STEP, PROMINENCE));
    }

    /** A gentle slope has a highest point; it does not have a high on it. */
    @Test
    void aSlopeWithNoClosedCentreIsNotMarked() {
        final float[] field = new float[COLS * ROWS];
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) field[y * COLS + x] = 101_000 + x * 4f;
        }

        assertEquals(List.of(),
                     IsolineLayer.centresOf(field, COLS, ROWS, STEP, PROMINENCE));
    }

    /**
     * Two bumps along one ridge are one system. They are far enough apart to
     * survive the distance test, and it is the shallow ground between them that
     * has to merge them - no isobar can close around either alone.
     */
    @Test
    void twoBumpsOnOneRidgeAreOneHigh() {
        final float[] field = flat(101_000);
        bump(field, 65, 45, 800, 15);          // the ridge itself
        bump(field, 35, 45, 150, 8);           // two slight maxima along it
        bump(field, 95, 45, 150, 8);

        final List<IsolineLayer.Centre> centres =
                IsolineLayer.centresOf(field, COLS, ROWS, STEP, PROMINENCE);

        assertEquals(1, centres.size(), centres.toString());
        assertTrue(centres.get(0).high());
    }

    /** Two real systems with a col between them stay two. */
    @Test
    void twoSeparateHighsAreNotMerged() {
        final float[] field = flat(101_000);
        bump(field, 30, 45, 1600, 9);
        bump(field, 100, 45, 1600, 9);

        final List<IsolineLayer.Centre> centres =
                IsolineLayer.centresOf(field, COLS, ROWS, STEP, PROMINENCE);

        assertEquals(2, centres.size(), centres.toString());
    }
}
