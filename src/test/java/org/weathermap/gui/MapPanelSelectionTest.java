package org.weathermap.gui;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which corner of a drag is the west one.
 *
 * <p>The answer is "whichever was further left on screen", not "whichever is
 * the smaller number". They agree everywhere except across the antimeridian,
 * where taking the smaller number selects every part of the world <em>except</em>
 * the part the pointer was dragged over.</p>
 */
class MapPanelSelectionTest {

    @Test
    void aDragAcrossTheSeamSelectsWhatWasUnderIt() {
        final BoundingBox bbox = MapPanel.selectionBetween(
                new double[]{10, 170}, new double[]{-10, -170});

        assertTrue(bbox.crossesAntimeridian());
        assertEquals(170, bbox.west(), 1e-9);
        assertEquals(-170, bbox.east(), 1e-9);
        assertEquals(20, bbox.widthDegrees(), 1e-9, "twenty degrees, not three hundred and forty");
        assertEquals(-10, bbox.south(), 1e-9);
        assertEquals(10, bbox.north(), 1e-9);
    }

    @Test
    void anOrdinaryDragIsUnchanged() {
        final BoundingBox bbox = MapPanel.selectionBetween(
                new double[]{61, -11}, new double[]{49.5, 2});

        assertFalse(bbox.crossesAntimeridian());
        assertEquals(-11, bbox.west(), 1e-9);
        assertEquals(2, bbox.east(), 1e-9);
        assertEquals(49.5, bbox.south(), 1e-9);
        assertEquals(61, bbox.north(), 1e-9);
    }

    /** The corner at exactly 180 is the east edge, so it is written as 180. */
    @Test
    void anEastEdgeOnTheSeamIsNotFoldedToTheWest() {
        final BoundingBox bbox = MapPanel.selectionBetween(
                new double[]{10, 170}, new double[]{-10, -180});

        assertEquals(180, bbox.east(), 1e-9);
        assertEquals(10, bbox.widthDegrees(), 1e-9);
    }
}
