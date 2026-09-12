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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The chart's title, its legend and its file name have to agree about what it
 * is a chart of. They used to be chosen in two places by two different rules.
 */
class CompositorPrimaryTest {

    private static final BoundingBox BOX = BoundingBox.of(49, -3, 52, 3);

    private static Grid grid(GribVariable variable, GribLevel level, String validTime) {
        return new Grid(variable, level, Instant.parse(validTime), BOX, 1, 1, new float[]{1f});
    }

    /**
     * The reported case: wind and precipitation, where UGRD decodes first and
     * so named the file, while the title named precipitation.
     */
    @Test
    void windComponentsAreNeverTheSubject() {
        final Grid u = grid(GribCatalog.WIND_U, GribCatalog.LEVEL_10M, "2026-09-12T06:00:00Z");
        final Grid v = grid(GribCatalog.WIND_V, GribCatalog.LEVEL_10M, "2026-09-12T06:00:00Z");
        final Grid rain = grid(GribCatalog.PRECIPITATION, GribCatalog.LEVEL_SURFACE,
                               "2026-09-12T00:00:00Z");

        assertSame(rain, Compositor.primaryOf(List.of(u, v, rain)));
    }

    /** With nothing but wind there is no shaded field, so a component has to do. */
    @Test
    void windAloneFallsBackToAComponent() {
        final Grid u = grid(GribCatalog.WIND_U, GribCatalog.LEVEL_10M, "2026-09-12T06:00:00Z");
        final Grid v = grid(GribCatalog.WIND_V, GribCatalog.LEVEL_10M, "2026-09-12T06:00:00Z");

        assertSame(u, Compositor.primaryOf(List.of(u, v)));
    }

    @Test
    void nothingDecodedHasNoSubject() {
        assertNull(Compositor.primaryOf(List.of()));
        assertNull(Compositor.validTimeOf(List.of()));
    }

    /**
     * Accumulated precipitation for forecast hour 6 carries 00:00 - the start of
     * the window it accumulated over - so taking the primary field's own time
     * captioned a chart of 06:00 winds as "valid 00:00".
     */
    @Test
    void theChartIsStampedWithTheForecastInstantNotTheAccumulationWindowStart() {
        final Grid u = grid(GribCatalog.WIND_U, GribCatalog.LEVEL_10M, "2026-09-12T06:00:00Z");
        final Grid rain = grid(GribCatalog.PRECIPITATION, GribCatalog.LEVEL_SURFACE,
                               "2026-09-12T00:00:00Z");

        assertEquals(Instant.parse("2026-09-12T06:00:00Z"),
                     Compositor.validTimeOf(List.of(u, rain)));
    }
}
