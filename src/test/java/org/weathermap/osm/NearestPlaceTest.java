package org.weathermap.osm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming a clicked point. The distance matters as much as the name: it is what
 * tells the user whether the answer is "this harbour" or "the nearest thing in
 * the data, a long way off".
 */
class NearestPlaceTest {

    private static Feature place(String name, String type, double lat, double lon) {
        return new Feature(FeatureKind.PLACE, List.of(new double[]{lat, lon}),
                           Map.of("name", name, "place", type));
    }

    private static Feature sea(String name, double lat, double lon) {
        return new Feature(FeatureKind.MARINE, List.of(new double[]{lat, lon}),
                           Map.of("name", name));
    }

    private static final List<Feature> FEATURES = List.of(
            place("Vung Tau", "town", 10.346, 107.084),
            place("Ho Chi Minh City", "city", 10.823, 106.630),
            place("Phan Thiet", "town", 10.933, 108.100),
            sea("South China Sea", 13.0, 114.0),
            new Feature(FeatureKind.COASTLINE,
                        List.of(new double[]{10.30, 107.05}, new double[]{10.40, 107.10}),
                        Map.of("natural", "coastline")));

    @Test
    void findsTheNearestNamedPlace() {
        final NearestPlace.Match match =
                NearestPlace.near(10.35, 107.09, FEATURES, NearestPlace.NAMEABLE);

        assertNotNull(match);
        assertEquals("Vung Tau", match.name());
        assertTrue(match.distanceKm() < 1, "within a kilometre: " + match.distanceKm());
    }

    @Test
    void describesItWithItsKindAndHowFar() {
        final NearestPlace.Match match =
                NearestPlace.near(10.80, 106.60, FEATURES, NearestPlace.NAMEABLE);
        assertTrue(match.describe().startsWith("Ho Chi Minh City (city) — "), match.describe());
        assertTrue(match.describe().endsWith("away"), match.describe());
    }

    /**
     * A line has no label point, and its nearest vertex is where a surveyor
     * put a node rather than a place anyone could name.
     */
    @Test
    void ignoresCoastlinesAndOtherLines() {
        final NearestPlace.Match match =
                NearestPlace.near(10.35, 107.07, FEATURES, NearestPlace.NAMEABLE);
        assertEquals(FeatureKind.PLACE, match.kind());
    }

    @Test
    void canBeAskedForTownsAlone() {
        // Right on the sea's own label point, which is still not a town.
        final NearestPlace.Match match = NearestPlace.nearestTown(13.0, 114.0, FEATURES);
        assertEquals(FeatureKind.PLACE, match.kind());

        final NearestPlace.Match any =
                NearestPlace.near(13.0, 114.0, FEATURES, NearestPlace.NAMEABLE);
        assertEquals("South China Sea", any.name());
    }

    @Test
    void findsNothingInDataWithNoNamesInIt() {
        assertNull(NearestPlace.near(0, 0, List.of(), NearestPlace.NAMEABLE));
        assertNull(NearestPlace.near(0, 0,
                List.of(new Feature(FeatureKind.PLACE, List.of(new double[]{0, 0}), Map.of())),
                NearestPlace.NAMEABLE), "an unnamed place cannot name anything");
        assertNull(NearestPlace.near(0, 0, FEATURES, Set.of(FeatureKind.BOUNDARY)));
    }

    // ---- distance ------------------------------------------------------------

    @Test
    void measuresGreatCircleDistance() {
        // A degree of latitude is about 111 km anywhere.
        assertEquals(111.2, NearestPlace.distanceKm(0, 0, 1, 0), 0.5);
        assertEquals(0, NearestPlace.distanceKm(51.5, -0.1, 51.5, -0.1), 1e-9);
        // London to Paris, near enough.
        assertEquals(344, NearestPlace.distanceKm(51.507, -0.128, 48.857, 2.352), 5);
    }

    /**
     * Haversine needs no antimeridian case of its own, which is worth pinning:
     * the alternative, subtracting longitudes, calls these two points three
     * hundred and fifty-eight degrees apart.
     */
    @Test
    void measuresStraightThroughTheAntimeridian() {
        final double across = NearestPlace.distanceKm(0, 179, 0, -179);
        assertEquals(222.4, across, 1.0);
        assertEquals(across, NearestPlace.distanceKm(0, -179, 0, 179), 1e-9);
    }

    @Test
    void picksTheRightSideOfTheSeam() {
        final List<Feature> pacific = List.of(
                place("Suva", "city", -18.14, 178.44),
                place("Nukualofa", "city", -21.14, -175.20));

        assertEquals("Nukualofa",
                NearestPlace.near(-21.0, -175.5, pacific, NearestPlace.NAMEABLE).name());
        assertEquals("Suva",
                NearestPlace.near(-18.0, 179.0, pacific, NearestPlace.NAMEABLE).name());
    }

    @Test
    void writesTheDistanceInAUnitThatSuitsIt() {
        assertTrue(NearestPlace.near(10.3461, 107.0841, FEATURES, NearestPlace.NAMEABLE)
                .distance().endsWith(" m"), "metres when it is close");
        assertTrue(NearestPlace.near(10.80, 106.60, FEATURES, NearestPlace.NAMEABLE)
                .distance().endsWith(" km"));
    }
}
