package org.weathermap.gui;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Panning used to stop dead at 180&deg;, which is what made a Pacific area
 * impossible to choose in one go: the view could be walked up to the
 * antimeridian and no further, so the area had to be taken in two halves and
 * the charts joined by hand afterwards.
 */
class MapViewTest {

    @Test
    void panningEastPastTheSeamComesOutTheOtherSide() {
        // Centred on 174, panned twenty degrees east: 194, which is -166.
        final MapView view = new MapView(BoundingBox.of(-10, 170, 10, 178));
        view.panBy(0, 20);

        assertEquals(-166, view.centreLon(), 1e-9);
    }

    @Test
    void theViewItselfMayCrossTheSeam() {
        final MapView view = new MapView(BoundingBox.of(-10, 170, 10, -170));
        final BoundingBox bounds = view.bounds(400, 200);

        assertTrue(bounds.crossesAntimeridian());
        assertEquals(25, bounds.widthDegrees(), 1e-6, "twenty degrees and the usual margin");
        assertTrue(bounds.contains(0, 180));
    }

    @Test
    void zoomingAboutAPointAcrossTheSeamStaysThere() {
        // The centre is just west of the antimeridian and the pointer just
        // east of it. Taking the difference without wrapping sent the map most
        // of the way round the world on one wheel notch.
        final MapView view = new MapView(BoundingBox.of(-10, 175, 10, 179));
        view.zoomAbout(1 / 1.25, 0, -179);

        assertTrue(view.centreLon() > 170 || view.centreLon() < -170,
                   "still near the seam, not at " + view.centreLon());
    }

    @Test
    void theWholeWorldIsStillWrittenTheOrdinaryWayRound() {
        final MapView view = new MapView(BoundingBox.of(-80, -180, 80, 180));
        final BoundingBox bounds = view.bounds(400, 200);

        assertFalse(bounds.crossesAntimeridian());
        assertEquals(-180, bounds.west(), 1e-9);
        assertEquals(180, bounds.east(), 1e-9);
    }

    @Test
    void anOrdinaryViewIsUnchanged() {
        final MapView view = new MapView(BoundingBox.of(49.5, -11, 61, 2));
        final BoundingBox bounds = view.bounds(1300, 1150);

        assertFalse(bounds.crossesAntimeridian());
        // Thirteen degrees framed with the usual quarter of margin.
        assertEquals(-12.625, bounds.west(), 1e-6);
        assertEquals(3.625, bounds.east(), 1e-6);
    }
}
