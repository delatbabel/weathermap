package org.weathermap.model;

import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A box across 180&deg; projects like any other, which is what lets one image,
 * one compositor and one set of layers serve a Pacific chart.
 */
class AntimeridianProjectionTest {

    private static final BoundingBox PACIFIC = BoundingBox.of(-10, 170, 10, -170);

    @Test
    void theSeamFallsInTheMiddleOfTheImageNotAtItsEdge() {
        final MapProjection p = new EquirectangularProjection(PACIFIC, 400, 200);

        assertEquals(0, p.toPixel(0, 170).x, 1e-6, "the west edge");
        assertEquals(200, p.toPixel(0, 180).x, 1e-6, "the antimeridian, halfway across");
        assertEquals(200, p.toPixel(0, -180).x, 1e-6, "and again, written the other way");
        assertEquals(400, p.toPixel(0, -170).x, 1e-6, "the east edge");
    }

    @Test
    void longitudesEitherSideOfTheSeamStayNeighbours() {
        final MapProjection p = new EquirectangularProjection(PACIFIC, 400, 200);
        final double justWest = p.toPixel(0, 179.5).x;
        final double justEast = p.toPixel(0, -179.5).x;

        // A degree apart on the ground is a degree apart on the page. Without
        // the wrap these two landed at opposite ends of the image and every
        // coastline between them was drawn as a line across the chart.
        assertEquals(20, justEast - justWest, 1e-6);
    }

    @Test
    void pixelsComeBackAsLongitudesInRange() {
        final MapProjection p = new EquirectangularProjection(PACIFIC, 400, 200);

        assertEquals(175, p.toLatLon(100, 100)[1], 1e-6);
        // Three quarters across is 185 east of the west edge, which is -175.
        assertEquals(-175, p.toLatLon(300, 100)[1], 1e-6);
    }

    @Test
    void roundTripsThroughBothProjections() {
        for (MapProjection p : new MapProjection[]{
                new EquirectangularProjection(PACIFIC, 400, 200),
                new MercatorProjection(PACIFIC, 400, 200)}) {
            for (double lon : new double[]{170.5, 178, 180, -178, -170.5}) {
                final Point2D.Double pixel = p.toPixel(3, lon);
                final double back = p.toLatLon(pixel.x, pixel.y)[1];
                assertEquals(BoundingBox.normaliseLon(lon), back, 1e-6,
                             p.getClass().getSimpleName() + " at " + lon);
                assertTrue(pixel.x >= -1e-6 && pixel.x <= 400 + 1e-6, "on the page: " + pixel.x);
            }
        }
    }

    /** A box that does not cross must be untouched by any of this. */
    @Test
    void anOrdinaryBoxProjectsExactlyAsItDid() {
        final BoundingBox uk = BoundingBox.of(49.5, -11, 61, 2);
        final MapProjection p = new EquirectangularProjection(uk, 1300, 1150);

        assertEquals(0, p.toPixel(55, -11).x, 1e-6);
        assertEquals(1300, p.toPixel(55, 2).x, 1e-6);
        assertEquals(100, p.toPixel(55, -10).x, 1e-6);
        // And a point off the western edge is off it, not round the far side.
        assertEquals(-100, p.toPixel(55, -12).x, 1e-6);
    }
}
