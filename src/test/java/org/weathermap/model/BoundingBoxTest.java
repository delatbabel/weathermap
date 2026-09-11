package org.weathermap.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundingBoxTest {

    @Test
    void rejectsInvertedCorners() {
        assertThrows(IllegalArgumentException.class,
                () -> BoundingBox.of(61, -11, 49.5, 2), "north below south");
        assertThrows(IllegalArgumentException.class,
                () -> BoundingBox.of(49.5, 2, 61, -11), "east left of west");
    }

    @Test
    void rejectsTheAntimeridian() {
        // Explicitly refused rather than silently split: see the class javadoc.
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BoundingBox.of(-10, 170, 10, -170));
        assertTrue(e.getMessage().contains("antimeridian"), e.getMessage());
    }

    @Test
    void rejectsDegenerateBoxes() {
        assertThrows(IllegalArgumentException.class, () -> BoundingBox.of(50, 0, 50.001, 1));
    }

    @Test
    void roundTripsThroughItsStringForm() {
        final BoundingBox original = BoundingBox.of(49.5, -11, 61, 2);
        assertEquals(original, BoundingBox.parse(original.toString()));
    }

    @Test
    void overpassOrderIsSouthWestNorthEast() {
        // Overpass wants s,w,n,e while toString() emits w,s,e,n - getting these
        // the same way round would silently query the wrong rectangle.
        assertEquals("49.50000,-11.00000,61.00000,2.00000",
                BoundingBox.of(49.5, -11, 61, 2).toOverpassBbox());
    }
}
