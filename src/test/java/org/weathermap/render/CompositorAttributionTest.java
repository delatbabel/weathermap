package org.weathermap.render;

import org.junit.jupiter.api.Test;
import org.weathermap.osm.Feature;
import org.weathermap.osm.FeatureKind;
import org.weathermap.osm.WorldGazetteer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chart credits the data it was drawn from. OpenStreetMap's licence requires
 * it when OSM data is present; printing it when none is present is a small
 * untruth on every copy, which is what the wide-area charts were doing.
 */
class CompositorAttributionTest {

    private static Feature osm() {
        return new Feature(FeatureKind.PLACE, List.of(new double[]{51, 0}),
                           Map.of("name", "London", "place", "city"));
    }

    private static Feature bundled() {
        return new Feature(FeatureKind.MARINE, List.of(new double[]{56, 3}),
                           Map.of("name", "North Sea",
                                  WorldGazetteer.SOURCE_TAG, WorldGazetteer.SOURCE));
    }

    @Test
    void osmDataIsCredited() {
        assertEquals(List.of(AnnotationLayer.OSM_ATTRIBUTION),
                     Compositor.attributionsFor(List.of(osm())));
    }

    @Test
    void aChartDrawnOnlyFromTheBundleDoesNotClaimOpenStreetMap() {
        assertEquals(List.of(WorldGazetteer.ATTRIBUTION),
                     Compositor.attributionsFor(List.of(bundled())));
    }

    @Test
    void bothAreCreditedWhenBothWereUsed() {
        final List<String> credits = Compositor.attributionsFor(List.of(osm(), bundled()));
        assertTrue(credits.contains(AnnotationLayer.OSM_ATTRIBUTION), credits.toString());
        assertTrue(credits.contains(WorldGazetteer.ATTRIBUTION), credits.toString());
    }

    /** No base map at all still leaves the forecast credit, which is added later. */
    @Test
    void noFeaturesCreditsNoMapData() {
        assertEquals(List.of(), Compositor.attributionsFor(List.of()));
    }
}
