package org.weathermap.osm;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverpassSpanTest {

    /** The box that prompted the guard: 56 degrees over South-East Asia. */
    @Test
    void aContinentSizedBoxIsRefusedBeforeItIsAsked() {
        assertTrue(OverpassClient.isTooLarge(BoundingBox.of(-10, 70, 10, 126)));
    }

    @Test
    void anOrdinaryChartAreaIsServed() {
        // Northern Britain - fourteen degrees across, the area the renders use.
        assertFalse(OverpassClient.isTooLarge(BoundingBox.of(49.5, -11, 60, 3)));
    }

    /** Height counts as much as width; a tall thin box is just as expensive. */
    @Test
    void theLongerSideDecides() {
        assertTrue(OverpassClient.isTooLarge(BoundingBox.of(0, 0, 2, 30)));
        assertTrue(OverpassClient.isTooLarge(BoundingBox.of(0, 0, 30, 2)));
    }
}
