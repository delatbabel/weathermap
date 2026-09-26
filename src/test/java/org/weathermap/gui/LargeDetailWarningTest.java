package org.weathermap.gui;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;
import org.weathermap.osm.FeatureKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thirteen degrees of South-East Asia came back from Overpass as a hundred and
 * eight megabytes. Unannounced, a minute of waiting for that reads as the
 * button having done nothing.
 */
class LargeDetailWarningTest {

    private static final List<FeatureKind> WITH_COAST =
            List.of(FeatureKind.COASTLINE, FeatureKind.BOUNDARY, FeatureKind.PLACE);

    @Test
    void asksBeforeAWideCoastlineQuery() {
        final String said = MainWindow.largeDetailWarning(
                BoundingBox.of(4.3, 97.9, 17.5, 114.3), WITH_COAST);

        assertNotNull(said);
        assertTrue(said.contains("16° across"), said);
        // The cache is the reassurance: this is a one-off for this area.
        assertTrue(said.contains("four weeks"), said);
    }

    @Test
    void saysNothingAboutASmallOne() {
        assertNull(MainWindow.largeDetailWarning(BoundingBox.of(53, -4, 55, -2), WITH_COAST),
                   "two degrees is quick enough that asking is an interruption");
    }

    /**
     * Place names are small at any scale - they are what the automatic path
     * fetches on every pan. Only the coastline is worth stopping for.
     */
    @Test
    void saysNothingWhenNoCoastlineWasAskedFor() {
        assertNull(MainWindow.largeDetailWarning(
                BoundingBox.of(0, 0, 18, 18), List.of(FeatureKind.PLACE)));
    }

    @Test
    void measuresTheLongerSideOfTheView() {
        // Narrow but very tall is just as expensive as the other way round.
        assertNotNull(MainWindow.largeDetailWarning(
                BoundingBox.of(0, 0, 15, 2), WITH_COAST));
        assertNull(MainWindow.largeDetailWarning(
                BoundingBox.of(0, 0, 5, 2), WITH_COAST));
    }
}
