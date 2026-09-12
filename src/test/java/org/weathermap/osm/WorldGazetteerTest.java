package org.weathermap.osm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGazetteerTest {

    @Test
    void theBundledResourceLoads() {
        final List<Feature> all = WorldGazetteer.features();
        assertFalse(all.isEmpty(), "gazetteer resource missing from the build");

        for (Feature f : all) {
            assertEquals(1, f.points().size(), "a label is one point: " + f.name());
            assertTrue(WorldGazetteer.isBundled(f));
            assertFalse(f.name() == null || f.name().isBlank());
        }
    }

    @Test
    void allThreeKindsArePresent() {
        assertFalse(WorldGazetteer.of(FeatureKind.MARINE).isEmpty());
        assertFalse(WorldGazetteer.of(FeatureKind.COUNTRY).isEmpty());
        assertFalse(WorldGazetteer.of(FeatureKind.PLACE).isEmpty());
    }

    /**
     * The example that prompted the gazetteer: winds off the Bay of Bengal and
     * the Gulf of Thailand reach southern Vietnam separately, and a chart that
     * names neither cannot be read for that.
     *
     * <p>The 1:110m marine set has 29 features and no Gulf of Thailand, so this
     * also pins the choice of the 1:50m source.</p>
     */
    @Test
    void theSeasThatMatterAreNamedAndInTheRightWater() {
        assertLabelNear("Bay of Bengal", 15.0, 88.0, 4.0);
        assertLabelNear("Gulf of Thailand", 9.5, 101.5, 2.0);
        assertLabelNear("Andaman Sea", 11.0, 95.5, 3.0);
        assertLabelNear("South China Sea", 13.0, 114.0, 4.0);
    }

    /**
     * A label point has to fall inside its own water, which a polygon centroid
     * does not for a crescent - the Gulf of Thailand's centroid is in Thailand.
     * Checked here rather than in the tool because the resource is what ships.
     */
    @Test
    void marineLabelsSitOnWaterNotOnTheirBoundingBox() {
        // The English Channel is the sharpest case in the set: a long thin
        // shape whose bounding-box centre is in Normandy.
        assertLabelNear("English Channel", 49.9, -2.5, 1.5);
    }

    private static void assertLabelNear(String name, double lat, double lon, double tolerance) {
        final Feature found = WorldGazetteer.of(FeatureKind.MARINE).stream()
                .filter(f -> name.equals(f.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not in the gazetteer"));

        final double[] p = found.points().get(0);
        assertTrue(Math.abs(p[0] - lat) <= tolerance && Math.abs(p[1] - lon) <= tolerance,
                   name + " label is at " + p[0] + "," + p[1]
                           + " - expected near " + lat + "," + lon);
    }
}
