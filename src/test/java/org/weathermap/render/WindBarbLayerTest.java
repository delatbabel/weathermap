package org.weathermap.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WindBarbLayerTest {

    /**
     * The direction convention, which is the one thing on this map that is
     * silently wrong if it is backwards: a barb pointing the wrong way is still
     * a plausible-looking barb.
     *
     * <p>u is eastward and v northward, so a wind blowing <em>toward</em> the
     * east (u positive) is a <b>westerly</b> - it comes from 270°.</p>
     */
    @Test
    void directionIsWhereTheWindComesFrom() {
        assertEquals(270, WindBarbLayer.directionFrom(10, 0), 0.001, "blowing east = westerly");
        assertEquals(90, WindBarbLayer.directionFrom(-10, 0), 0.001, "blowing west = easterly");
        assertEquals(180, WindBarbLayer.directionFrom(0, 10), 0.001, "blowing north = southerly");
        assertEquals(0, WindBarbLayer.directionFrom(0, -10), 0.001, "blowing south = northerly");
        assertEquals(225, WindBarbLayer.directionFrom(7.07, 7.07), 0.01, "south-westerly");
    }

    @Test
    void knotsFromMetresPerSecond() {
        assertEquals(19.438, WindBarbLayer.knots(10, 0), 0.001);
        assertEquals(27.490, WindBarbLayer.knots(10, 10), 0.001);
    }

    /** Pennants, full ticks and a half tick, in that order. */
    @Test
    void decomposesSpeedTheWayTheNotationDoes() {
        assertArrayEquals(new int[]{0, 0, 0}, WindBarbLayer.decompose(0));
        assertArrayEquals(new int[]{0, 0, 1}, WindBarbLayer.decompose(5));
        assertArrayEquals(new int[]{0, 1, 0}, WindBarbLayer.decompose(10));
        assertArrayEquals(new int[]{0, 1, 1}, WindBarbLayer.decompose(15));
        assertArrayEquals(new int[]{0, 4, 1}, WindBarbLayer.decompose(45));
        assertArrayEquals(new int[]{1, 0, 0}, WindBarbLayer.decompose(50));
        assertArrayEquals(new int[]{1, 1, 1}, WindBarbLayer.decompose(65));
        assertArrayEquals(new int[]{2, 0, 0}, WindBarbLayer.decompose(100));
        assertArrayEquals(new int[]{2, 2, 1}, WindBarbLayer.decompose(125));
    }

    /**
     * Speed is rounded to the nearest 5 before being split up, because the
     * notation cannot express anything finer - and rounding afterwards gives
     * barbs that do not add up to the speed they claim.
     */
    @Test
    void roundsToTheNearestFiveKnots() {
        assertArrayEquals(new int[]{0, 1, 0}, WindBarbLayer.decompose(12.4));
        assertArrayEquals(new int[]{0, 1, 1}, WindBarbLayer.decompose(12.6));
        assertArrayEquals(new int[]{1, 0, 0}, WindBarbLayer.decompose(47.6));
    }
}
