package org.weathermap.gui;

import org.junit.jupiter.api.Test;
import org.weathermap.osm.Feature;
import org.weathermap.osm.FeatureKind;
import org.weathermap.osm.NearestPlace;
import org.weathermap.tide.TidePoint;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The question asked before a click is turned into two API requests.
 *
 * <p>Its job is to let someone tell at a glance whether they clicked where
 * they meant to, which a latitude and a longitude cannot do on their own, and
 * to say what pressing the button will cost.</p>
 */
class TidePointMessageTest {

    private static final TidePoint POINT = new TidePoint("Vung Tau", 10.3461, 107.0843);

    private static NearestPlace.Match match(String name, double lat, double lon) {
        return NearestPlace.near(POINT.lat(), POINT.lon(),
                List.of(new Feature(FeatureKind.PLACE, List.of(new double[]{lat, lon}),
                                    Map.of("name", name, "place", "town"))),
                NearestPlace.NAMEABLE);
    }

    @Test
    void namesThePlaceAndGivesThePosition() {
        final String said = MainWindow.tidePointMessage(POINT, match("Vung Tau", 10.35, 107.08), false);

        assertTrue(said.contains("Vung Tau"), said);
        assertTrue(said.contains("10.3461°N"), said);
        assertTrue(said.contains("107.0843°E"), said);
    }

    @Test
    void saysWhatItWillCost() {
        final String said = MainWindow.tidePointMessage(POINT, match("Vung Tau", 10.35, 107.08), false);
        assertTrue(said.contains("costs 2 of today's requests"), said);
        assertTrue(said.contains("10 days"), said);
    }

    /**
     * And says when it will cost nothing, which is the number that decides
     * whether idly comparing four harbours is reasonable or reckless.
     */
    @Test
    void saysWhenItIsFree() {
        final String said = MainWindow.tidePointMessage(POINT, match("Vung Tau", 10.35, 107.08), true);
        assertTrue(said.contains("costs no requests"), said);
        assertFalse(said.contains("costs 2"), said);
    }

    @Test
    void saysNothingAlarmingAboutANameThatIsRightThere() {
        final String said = MainWindow.tidePointMessage(POINT, match("Vung Tau", 10.35, 107.08), false);
        assertTrue(said.contains("Nearest place in the OSM data"), said);
        assertFalse(said.contains("a long way off"), said);
        assertFalse(said.contains("Load detail"), said);
    }

    /**
     * A distant name from OSM means the point really is out in the middle of
     * nothing - which is worth saying, and is not fixed by loading more data.
     */
    @Test
    void saysSoWhenTheNearestNameIsGenuinelyFarAway() {
        final String said = MainWindow.tidePointMessage(POINT, match("Somewhere", 14.0, 107.0), false);
        assertTrue(said.contains("a long way off"), said);
        // Loading detail cannot conjure a town that is not there.
        assertFalse(said.contains("Load detail"), said);
    }

    /**
     * The same name and distance from the <em>bundled</em> set means something
     * else entirely: the nearest named place may be round the corner and
     * simply not shipped. The two read identically otherwise, and only this
     * one is worth doing something about.
     */
    @Test
    void distinguishesABundledNameFromAnOsmOne() {
        final NearestPlace.Match bundled = NearestPlace.near(POINT.lat(), POINT.lon(),
                List.of(new Feature(FeatureKind.PLACE, List.of(new double[]{10.82, 106.63}),
                        Map.of("name", "Ho Chi Minh City", "place", "city",
                               org.weathermap.osm.WorldGazetteer.SOURCE_TAG,
                               org.weathermap.osm.WorldGazetteer.SOURCE))),
                NearestPlace.NAMEABLE);

        final String said = MainWindow.tidePointMessage(POINT, bundled, false);
        assertTrue(said.contains("bundled world gazetteer"), said);
        assertTrue(said.contains("not necessarily the nearest place"), said);
        assertTrue(said.contains("Load detail"), said);
    }

    @Test
    void copesWithNothingNamedAnywhereNear() {
        final String said = MainWindow.tidePointMessage(POINT, null, false);
        assertTrue(said.contains("No named place"), said);
        assertTrue(said.contains("Load map detail"), said);
        assertTrue(said.contains("costs 2 of today's requests"), said);
    }

    /**
     * The message is HTML and the name in it comes from map data. OSM names
     * are free text typed by strangers.
     */
    @Test
    void escapesThePlaceNameItWasGiven() {
        final String said = MainWindow.tidePointMessage(
                POINT, match("Fish & Chips <b>", 10.35, 107.08), false);

        assertTrue(said.contains("Fish &amp; Chips &lt;b&gt;"), said);
        assertFalse(said.contains("Chips <b>"), said);
    }
}
