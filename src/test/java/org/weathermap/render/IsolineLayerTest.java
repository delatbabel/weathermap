package org.weathermap.render;

import org.junit.jupiter.api.Test;
import org.weathermap.grib.Grid;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribCatalog;
import org.weathermap.model.GribLevel;
import org.weathermap.model.GribVariable;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
