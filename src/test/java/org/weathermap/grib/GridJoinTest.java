package org.weathermap.grib;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribCatalog;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NOMADS cannot cut a subregion across 180&deg;, so a Pacific chart arrives as
 * two fields. These are the rules for making them one again.
 */
class GridJoinTest {

    private static final Instant VALID = Instant.parse("2026-09-26T00:00:00Z");

    /** A field whose value at every point is simply its own longitude. */
    private static Grid strip(double west, double east, int width) {
        final BoundingBox bounds = BoundingBox.of(-10, west, 10, east);
        final int height = 3;
        final float[] values = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                values[y * width + x] =
                        (float) (west + bounds.widthDegrees() * x / (width - 1));
            }
        }
        return new Grid(GribCatalog.TEMPERATURE, GribCatalog.LEVEL_2M, VALID,
                        bounds, width, height, values);
    }

    @Test
    void joinsTwoHalvesIntoOneCrossingField() {
        final Grid joined = Grid.join(strip(170, 180, 6), strip(-180, -170, 6));

        assertTrue(joined.bounds().crossesAntimeridian());
        assertEquals(170, joined.bounds().west(), 1e-9);
        assertEquals(-170, joined.bounds().east(), 1e-9);
        assertEquals(20, joined.bounds().widthDegrees(), 1e-9);

        // Eleven columns, not twelve: both halves include the antimeridian, so
        // one of the two copies of it is dropped rather than drawn twice.
        assertEquals(11, joined.width());
        assertEquals(3, joined.height());
    }

    @Test
    void theJoinedFieldSamplesContinuouslyThroughTheSeam() {
        final Grid joined = Grid.join(strip(170, 180, 6), strip(-180, -170, 6));

        assertEquals(170, joined.sample(0, 170), 1e-4);
        assertEquals(175, joined.sample(0, 175), 1e-4);
        assertEquals(180, joined.sample(0, 180), 1e-4);
        assertEquals(-175, joined.sample(0, -175), 1e-4);
        assertEquals(-170, joined.sample(0, -170), 1e-4);
    }

    @Test
    void keepsEveryColumnWhenTheHalvesMerelyAbut() {
        // Not every source repeats the seam column. When the eastern half
        // starts one step on instead of at the same meridian, nothing is
        // dropped.
        final Grid joined = Grid.join(strip(170, 180, 6), strip(-178, -170, 5));
        assertEquals(11, joined.width());
        assertEquals(-170, joined.bounds().east(), 1e-9);
    }

    @Test
    void refusesHalvesThatDoNotShareTheirRows() {
        final Grid west = strip(170, 180, 6);
        final Grid tall = new Grid(GribCatalog.TEMPERATURE, GribCatalog.LEVEL_2M, VALID,
                                   BoundingBox.of(-20, -180, 20, -170), 6, 3, new float[18]);
        assertThrows(IllegalArgumentException.class, () -> Grid.join(west, tall));
    }

    /** Sampling an ordinary field is unchanged by any of this. */
    @Test
    void anOrdinaryFieldStillSamplesAsItDid() {
        final Grid uk = strip(-11, 2, 14);
        assertEquals(-11, uk.sample(0, -11), 1e-4);
        assertEquals(0, uk.sample(0, 0), 1e-4);
        assertEquals(2, uk.sample(0, 2), 1e-4);
        assertTrue(Float.isNaN(uk.sample(0, 10)), "outside the field");
        assertTrue(Float.isNaN(uk.sample(0, -20)), "outside the field, to the west");
    }
}
