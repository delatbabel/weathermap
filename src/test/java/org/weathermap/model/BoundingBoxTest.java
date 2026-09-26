package org.weathermap.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundingBoxTest {

    /** Twenty degrees of Pacific, ten either side of the seam. */
    private static final BoundingBox PACIFIC = BoundingBox.of(-10, 170, 10, -170);

    @Test
    void rejectsInvertedLatitudes() {
        assertThrows(IllegalArgumentException.class,
                () -> BoundingBox.of(61, -11, 49.5, 2), "north below south");
    }

    @Test
    void rejectsDegenerateBoxes() {
        assertThrows(IllegalArgumentException.class, () -> BoundingBox.of(50, 0, 50.001, 1));
        assertThrows(IllegalArgumentException.class, () -> BoundingBox.of(50, 1, 51, 1),
                "a box whose edges are the same meridian has no width to speak of");
    }

    // ---- across the antimeridian ----------------------------------------

    /**
     * An east edge west of the west edge is the notation for crossing, not an
     * error. It has to be: there is no other way to write the twenty degrees
     * either side of 180 with two numbers in -180..180.
     */
    @Test
    void anEastEdgeBelowTheWestOneMeansItCrosses() {
        assertTrue(PACIFIC.crossesAntimeridian());
        assertEquals(20, PACIFIC.widthDegrees(), 1e-9);
        // The centre is the antimeridian itself, which normalises to -180:
        // the same line as 180, written the way a west edge is written.
        assertEquals(-180, PACIFIC.centreLon(), 1e-9);
    }

    @Test
    void measuresEastwardThroughTheSeam() {
        assertEquals(0, PACIFIC.eastwardFrom(170), 1e-9);
        assertEquals(10, PACIFIC.eastwardFrom(180), 1e-9);
        assertEquals(10, PACIFIC.eastwardFrom(-180), 1e-9, "the same meridian either way");
        assertEquals(20, PACIFIC.eastwardFrom(-170), 1e-9);
    }

    /**
     * A meridian just outside the west edge reads as a small negative distance
     * rather than as nearly a full turn, so a coastline entering the box is
     * drawn entering from the left rather than leaping in from the right.
     */
    @Test
    void justOutsideTheWestEdgeIsNegative() {
        assertEquals(-1, PACIFIC.eastwardFrom(169), 1e-9);
        assertEquals(-1, BoundingBox.of(49.5, -11, 61, 2).eastwardFrom(-12), 1e-9);
    }

    @Test
    void containsPointsOnBothSidesOfTheSeam() {
        assertTrue(PACIFIC.contains(0, 175));
        assertTrue(PACIFIC.contains(0, -175));
        assertTrue(PACIFIC.contains(0, 180));
        assertFalse(PACIFIC.contains(0, 0));
        assertFalse(PACIFIC.contains(0, 160));
    }

    @Test
    void containsSmallerBoxesThroughTheSeam() {
        assertTrue(PACIFIC.contains(BoundingBox.of(-5, 175, 5, -175)));
        assertTrue(PACIFIC.contains(BoundingBox.of(-5, 171, 5, 179)));
        assertFalse(PACIFIC.contains(BoundingBox.of(-5, 160, 5, -175)));
        assertTrue(BoundingBox.of(-80, -180, 80, 180).contains(PACIFIC),
                   "the world contains everything, however it is written");
    }

    @Test
    void growsThroughTheSeamInsteadOfClampingAtIt() {
        final BoundingBox grown = BoundingBox.of(-10, 175, 10, 179).expanded(10);
        assertTrue(grown.crossesAntimeridian());
        assertEquals(165, grown.west(), 1e-9);
        assertEquals(-171, grown.east(), 1e-9);
        assertEquals(24, grown.widthDegrees(), 1e-9);
    }

    @Test
    void growingPastAFullTurnGivesTheWholeWorld() {
        final BoundingBox grown = BoundingBox.of(-10, -175, 10, 175).expanded(20);
        assertEquals(-180, grown.west(), 1e-9);
        assertEquals(180, grown.east(), 1e-9);
        assertEquals(360, grown.widthDegrees(), 1e-9);
    }

    // ---- halves, for the services that cannot take a crossing box --------

    @Test
    void aBoxThatDoesNotCrossIsItsOwnOnlyHalf() {
        final BoundingBox uk = BoundingBox.of(49.5, -11, 61, 2);
        assertEquals(List.of(uk), uk.halves());
    }

    @Test
    void splitsAtTheSeamWestFirst() {
        final List<BoundingBox> halves = PACIFIC.halves();
        assertEquals(2, halves.size());
        assertEquals(BoundingBox.of(-10, 170, 10, 180), halves.get(0));
        assertEquals(BoundingBox.of(-10, -180, 10, -170), halves.get(1));
        assertEquals(PACIFIC.widthDegrees(),
                     halves.get(0).widthDegrees() + halves.get(1).widthDegrees(), 1e-9);
    }

    /**
     * A box that crosses by a hair gives one piece, not a sliver no service
     * would serve. A hundredth of a degree is below the resolution of every
     * source here.
     */
    @Test
    void doesNotSplitOffASliver() {
        assertEquals(1, BoundingBox.of(-10, 179.999, 10, -170).halves().size());
        assertEquals(1, BoundingBox.of(-10, 170, 10, -179.999).halves().size());
    }

    @Test
    void refusesToWriteACrossingBoxAsOneOverpassBbox() {
        // Silently emitting s,w,n,e with w > e would query the whole rest of
        // the world, slowly, and get it wrong.
        final IllegalStateException e =
                assertThrows(IllegalStateException.class, PACIFIC::toOverpassBbox);
        assertTrue(e.getMessage().contains("halves()"), e.getMessage());
    }

    // ---- the plain cases -------------------------------------------------

    @Test
    void roundTripsThroughItsStringForm() {
        final BoundingBox original = BoundingBox.of(49.5, -11, 61, 2);
        assertEquals(original, BoundingBox.parse(original.toString()));
        assertEquals(PACIFIC, BoundingBox.parse(PACIFIC.toString()));
    }

    @Test
    void overpassOrderIsSouthWestNorthEast() {
        // Overpass wants s,w,n,e while toString() emits w,s,e,n - getting these
        // the same way round would silently query the wrong rectangle.
        assertEquals("49.50000,-11.00000,61.00000,2.00000",
                BoundingBox.of(49.5, -11, 61, 2).toOverpassBbox());
    }

    @Test
    void normalisesLongitudeIntoItsTwoConventions() {
        assertEquals(-170, BoundingBox.normaliseLon(190), 1e-9);
        assertEquals(-180, BoundingBox.normaliseLon(180), 1e-9);
        assertEquals(180, BoundingBox.normaliseEastLon(180), 1e-9);
        assertEquals(180, BoundingBox.normaliseEastLon(-180), 1e-9);
        assertEquals(1, BoundingBox.normaliseLon(361), 1e-9);
    }
}
