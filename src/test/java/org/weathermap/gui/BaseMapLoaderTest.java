package org.weathermap.gui;

import org.junit.jupiter.api.Test;
import org.weathermap.MapService;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.RenderSpec;
import org.weathermap.osm.Feature;
import org.weathermap.osm.FeatureKind;
import org.weathermap.osm.OsmSource;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Load detail</b> is meant to show what the chart would show, minus the
 * weather. It did not: it asked for place names while its tooltip promised a
 * coastline, so on a view that already had its names pressing it changed
 * nothing visible and looked broken.
 */
class BaseMapLoaderTest {

    /** Records what it was asked for and answers with one feature per kind. */
    private static final class Source implements OsmSource {
        final List<List<FeatureKind>> asked = Collections.synchronizedList(new ArrayList<>());

        @Override
        public List<Feature> fetch(BoundingBox bbox, List<FeatureKind> kinds) {
            asked.add(List.copyOf(kinds));
            final List<Feature> out = new ArrayList<>();
            for (FeatureKind kind : kinds) {
                out.add(switch (kind) {
                    case COASTLINE -> new Feature(FeatureKind.COASTLINE,
                            List.of(new double[]{53, -3}, new double[]{54, -3}),
                            Map.of("natural", "coastline"));
                    case BOUNDARY -> new Feature(FeatureKind.BOUNDARY,
                            List.of(new double[]{53, -3}, new double[]{54, -2}),
                            Map.of("boundary", "administrative", "admin_level", "2"));
                    default -> new Feature(FeatureKind.PLACE,
                            List.of(new double[]{53.4, -2.9}),
                            Map.of("place", "town", "name", "Somewhere"));
                });
            }
            return out;
        }

        @Override
        public String description() { return "a test source"; }
    }

    /** Runs {@code action} on the EDT and waits for the loader to report back. */
    private static String awaitLoad(BaseMapLoader loader, Runnable action) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final String[] status = new String[1];
        SwingUtilities.invokeAndWait(() -> loader.setOnStatus(message -> {
            // The first message is "loading…"; the one that matters is the
            // last, which arrives with the features.
            status[0] = message;
        }));
        SwingUtilities.invokeAndWait(() -> loader.setOnLoaded(features -> done.countDown()));
        SwingUtilities.invokeAndWait(action);
        assertTrue(done.await(10, TimeUnit.SECONDS), "the loader never reported back");
        SwingUtilities.invokeAndWait(() -> { });        // drain the status update
        return status[0];
    }

    private static boolean has(List<Feature> features, FeatureKind kind) {
        return features.stream().anyMatch(f -> f.kind() == kind);
    }

    private static long count(List<Feature> features, FeatureKind kind) {
        return features.stream().filter(f -> f.kind() == kind).count();
    }

    // ---- the button ---------------------------------------------------------

    @Test
    void loadDetailAsksForWhatTheChartWouldAskFor() throws Exception {
        final Source source = new Source();
        final BaseMapLoader loader = new BaseMapLoader(source);
        final List<FeatureKind> kinds = MapService.featureKindsFor(new RenderSpec());

        final String status = awaitLoad(loader, () ->
                loader.loadNow(BoundingBox.of(53, -4, 55, -2), kinds));

        assertEquals(List.of(kinds), source.asked, "the chart's kinds, not place names only");
        assertTrue(kinds.contains(FeatureKind.COASTLINE), "the default chart draws a coastline");
        assertTrue(status.contains("coastline"), status);
    }

    /**
     * And the bundled outline gets out of the way. Both at once is not a richer
     * map, it is two coastlines a few kilometres apart.
     */
    @Test
    void realGeometryReplacesTheBundledOutlineRatherThanLayeringOverIt() throws Exception {
        final BaseMapLoader loader = new BaseMapLoader(new Source());
        final BoundingBox view = BoundingBox.of(53, -4, 55, -2);
        assertFalse(loader.hasDetailedShape(), "the outline to begin with");
        final long bundled = count(loader.features(), FeatureKind.COASTLINE);
        assertTrue(bundled > 1, "the bundled world outline is many features");

        SwingUtilities.invokeAndWait(() -> loader.viewChanged(view));
        awaitLoad(loader, () -> loader.loadNow(view,
                List.of(FeatureKind.COASTLINE, FeatureKind.PLACE)));

        assertTrue(loader.hasDetailedShape());
        assertEquals(1, count(loader.features(), FeatureKind.COASTLINE),
                     "only the one that was fetched");
        assertEquals(1, count(loader.features(), FeatureKind.PLACE),
                     "and the fetched place, not the bundled gazetteer's hundreds");
    }

    /**
     * Loading detail for one harbour and then zooming out must not leave that
     * harbour drawn on an empty world. The outline steps aside only while the
     * view is inside what was loaded.
     */
    @Test
    void theOutlineComesBackWhenTheViewLeavesTheDetailedArea() throws Exception {
        final BaseMapLoader loader = new BaseMapLoader(new Source());
        final BoundingBox small = BoundingBox.of(53, -4, 54, -3);

        SwingUtilities.invokeAndWait(() -> loader.viewChanged(small));
        awaitLoad(loader, () -> loader.loadNow(small,
                List.of(FeatureKind.COASTLINE, FeatureKind.PLACE)));

        assertFalse(loader.showsBundledOutline(), "inside the loaded area");
        assertEquals(1, count(loader.features(), FeatureKind.COASTLINE));

        // Zoom out past it.
        final List<Integer> pushes = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            loader.setOnLoaded(features -> pushes.add(features.size()));
            loader.viewChanged(BoundingBox.of(40, -20, 60, 10));
        });

        assertTrue(loader.showsBundledOutline(), "the world is visible again");
        assertTrue(count(loader.features(), FeatureKind.COASTLINE) > 1,
                   "and so is the world outline");
        assertEquals(1, pushes.size(), "the map has to be told, or it redraws the old list");

        // And back in.
        SwingUtilities.invokeAndWait(() -> loader.viewChanged(small));
        assertFalse(loader.showsBundledOutline());
        assertEquals(2, pushes.size());
    }

    /** With nowhere known to be looking, the outline stays: blank is worse. */
    @Test
    void theOutlineStaysWhileTheViewIsUnknown() throws Exception {
        final BaseMapLoader loader = new BaseMapLoader(new Source());
        awaitLoad(loader, () -> loader.loadNow(BoundingBox.of(53, -4, 55, -2),
                List.of(FeatureKind.COASTLINE)));

        assertTrue(loader.hasDetailedShape());
        assertTrue(loader.showsBundledOutline());
    }

    @Test
    void theOutlineIsNeverHiddenForPlaceNamesAlone() throws Exception {
        final BaseMapLoader loader = new BaseMapLoader(new Source());
        final BoundingBox small = BoundingBox.of(53, -4, 54, -3);

        SwingUtilities.invokeAndWait(() -> loader.viewChanged(small));
        awaitLoad(loader, () -> loader.loadNow(small, List.of(FeatureKind.PLACE)));

        assertTrue(loader.showsBundledOutline(), "names layer over the outline happily");
        assertTrue(count(loader.features(), FeatureKind.COASTLINE) > 1);
    }

    /** What was asked for, rather than what the caller passed in. */
    private static final class Boxes implements OsmSource {
        final List<BoundingBox> asked = Collections.synchronizedList(new ArrayList<>());

        @Override
        public List<Feature> fetch(BoundingBox bbox, List<FeatureKind> kinds) {
            asked.add(bbox);
            return List.of(new Feature(FeatureKind.COASTLINE,
                    List.of(new double[]{bbox.south(), bbox.west()},
                            new double[]{bbox.north(), bbox.east()}),
                    Map.of("natural", "coastline")));
        }

        @Override
        public String description() { return "a test source"; }
    }

    /**
     * The margin is asked for on top of the view, so the guard has to be on
     * the box that actually goes out.
     *
     * <p>It was on the view. A fifteen degree view passed the twenty degree
     * check and then sent a query for twenty-five and a half, which Overpass
     * will not serve - so zoom out, press Load detail, and nothing happens
     * that anyone can see.</p>
     */
    @Test
    void theMarginIsDroppedRatherThanSendingAQueryTooLargeToServe() throws Exception {
        final Boxes source = new Boxes();
        final BaseMapLoader loader = new BaseMapLoader(source);
        final BoundingBox view = BoundingBox.of(5, 100, 20, 115);        // 15 degrees

        awaitLoad(loader, () -> loader.loadNow(view, List.of(FeatureKind.COASTLINE)));

        assertEquals(1, source.asked.size());
        final BoundingBox sent = source.asked.get(0);
        assertEquals(view, sent, "the view itself, with the margin given up");
        assertFalse(org.weathermap.osm.OverpassClient.isTooLarge(sent),
                    "and therefore something Overpass will answer");
    }

    /** A small view still gets its margin, so a nudge afterwards needs no refetch. */
    @Test
    void aSmallViewIsStillFetchedWithRoomAroundIt() throws Exception {
        final Boxes source = new Boxes();
        final BaseMapLoader loader = new BaseMapLoader(source);
        final BoundingBox view = BoundingBox.of(53, -4, 54, -3);

        awaitLoad(loader, () -> loader.loadNow(view, List.of(FeatureKind.COASTLINE)));

        final BoundingBox sent = source.asked.get(0);
        assertTrue(sent.widthDegrees() > view.widthDegrees(), "margined: " + sent);
        assertTrue(sent.contains(view));
    }

    /**
     * Above the ceiling nothing is asked of Overpass - it would not answer -
     * and the bundled world map is used instead. Which is what the chart has
     * always done for the same area: refusing left the button doing nothing
     * on exactly the wide areas a saved profile recalls, while <b>Download
     * and composite</b> quietly drew a complete chart of the same place.
     */
    @Test
    void aViewBeyondTheLimitFallsBackToTheBundleRatherThanRefusing() throws Exception {
        final Boxes source = new Boxes();
        final BaseMapLoader loader = new BaseMapLoader(source);
        final String[] status = new String[1];
        final List<Integer> pushes = new ArrayList<>();

        SwingUtilities.invokeAndWait(() -> {
            loader.setOnStatus(message -> status[0] = message);
            loader.setOnLoaded(features -> pushes.add(features.size()));
            loader.loadNow(BoundingBox.of(0, 0, 25, 25), List.of(FeatureKind.COASTLINE));
        });

        assertEquals(List.of(), source.asked, "Overpass is not asked what it cannot answer");
        // The chart's own wording, because it is the chart's own behaviour.
        assertTrue(status[0].contains("using the bundled world map"), status[0]);
        assertEquals(1, pushes.size(), "and the map is told, or nothing appears to happen");
        assertTrue(loader.showsBundledOutline());
    }

    /** And whatever was loaded for somewhere else is dropped, not left over. */
    @Test
    void fallingBackToTheBundleDropsDetailFromElsewhere() throws Exception {
        final BaseMapLoader loader = new BaseMapLoader(new Source());
        final BoundingBox small = BoundingBox.of(53, -4, 54, -3);

        SwingUtilities.invokeAndWait(() -> loader.viewChanged(small));
        awaitLoad(loader, () -> loader.loadNow(small, List.of(FeatureKind.COASTLINE)));
        assertTrue(loader.hasDetailedShape());

        SwingUtilities.invokeAndWait(() ->
                loader.loadNow(BoundingBox.of(0, 0, 25, 25), List.of(FeatureKind.COASTLINE)));

        assertFalse(loader.hasDetailedShape());
        assertTrue(loader.showsBundledOutline());
    }

    /**
     * The sequence that was reported: zoom in, load, zoom out, load. The
     * second press has to reach the service, and for the wider area.
     */
    @Test
    void loadingAgainAfterZoomingOutFetchesTheWiderArea() throws Exception {
        final Boxes source = new Boxes();
        final BaseMapLoader loader = new BaseMapLoader(source);
        final BoundingBox small = BoundingBox.of(10, 106.5, 11, 107.5);
        final BoundingBox large = BoundingBox.of(5, 100, 20, 115);

        SwingUtilities.invokeAndWait(() -> loader.viewChanged(small));
        awaitLoad(loader, () -> loader.loadNow(small, List.of(FeatureKind.COASTLINE)));
        SwingUtilities.invokeAndWait(() -> loader.viewChanged(large));
        awaitLoad(loader, () -> loader.loadNow(large, List.of(FeatureKind.COASTLINE)));

        assertEquals(2, source.asked.size(), "the second press has to go out too");
        assertTrue(source.asked.get(1).contains(large),
                   "and cover where we are now: " + source.asked.get(1));
    }

    @Test
    void nothingIsAskedOfAServiceThatWouldRefuse() throws Exception {
        final Source source = new Source();
        final BaseMapLoader loader = new BaseMapLoader(source);
        final String[] status = new String[1];

        SwingUtilities.invokeAndWait(() -> {
            loader.setOnStatus(message -> status[0] = message);
            loader.loadNow(BoundingBox.of(0, 0, 40, 40), List.of(FeatureKind.COASTLINE));
        });

        assertEquals(List.of(), source.asked);
        assertTrue(status[0].contains("using the bundled world map"), status[0]);
        assertFalse(loader.hasDetailedShape());
    }

    // ---- the names the chart puts on ----------------------------------------

    /**
     * The selection map had no names at all above eight degrees - no seas, no
     * countries, no cities - while a chart of the same area had all three
     * from the same bundle. The map someone chooses an area on was emptier
     * than the thing it is for.
     */
    @Test
    void theBundledNamesAreThereFromTheStart() {
        final List<Feature> features = new BaseMapLoader(new Source()).features();

        assertTrue(has(features, FeatureKind.COASTLINE), "the outline");
        assertTrue(has(features, FeatureKind.MARINE), "the seas");
        assertTrue(has(features, FeatureKind.COUNTRY), "the countries");
        assertTrue(has(features, FeatureKind.PLACE), "and the cities");
    }

    /**
     * But bundled cities give way to OSM ones. Both sets at once puts two
     * dots on some of them and ranks them by two different meanings of
     * "important".
     */
    @Test
    void osmPlacesReplaceTheBundledOnesRatherThanJoiningThem() throws Exception {
        final BaseMapLoader loader = new BaseMapLoader(new Source());
        final long bundled = count(loader.features(), FeatureKind.PLACE);
        assertTrue(bundled > 100, "the bundled gazetteer is many cities");

        awaitLoad(loader, () -> loader.loadNow(BoundingBox.of(53, -4, 54, -3),
                List.of(FeatureKind.PLACE)));

        assertEquals(1, count(loader.features(), FeatureKind.PLACE),
                     "only the one that was fetched");
    }

    @Test
    void withEveryLayerOffThereIsNothingToLoad() throws Exception {
        final Source source = new Source();
        final BaseMapLoader loader = new BaseMapLoader(source);
        final RenderSpec spec = new RenderSpec();
        for (RenderSpec.LayerKind kind : RenderSpec.LayerKind.values()) spec.setEnabled(kind, false);
        final String[] status = new String[1];

        SwingUtilities.invokeAndWait(() -> {
            loader.setOnStatus(message -> status[0] = message);
            loader.loadNow(BoundingBox.of(53, -4, 55, -2), MapService.featureKindsFor(spec));
        });

        assertEquals(List.of(), source.asked);
        assertTrue(status[0].contains("No map layers"), status[0]);
    }

    // ---- the automatic path is unchanged --------------------------------------

    /**
     * Panning must still ask for names only. A coastline query repeated on
     * every pan is what got this client answered with 429s, and a button that
     * behaves differently is not a reason to change that.
     */
    @Test
    void panningStillAsksForPlaceNamesAloneAndKeepsTheOutline() throws Exception {
        final Source source = new Source();
        final BaseMapLoader loader = new BaseMapLoader(source);

        awaitLoad(loader, () -> loader.viewChanged(BoundingBox.of(53, -4, 55, -2)));

        assertEquals(List.of(List.of(FeatureKind.PLACE)), source.asked);
        assertFalse(loader.hasDetailedShape());
        assertTrue(count(loader.features(), FeatureKind.COASTLINE) > 1,
                   "the bundled outline is still underneath");
    }

    @Test
    void panningTooWideFetchesNothing() throws Exception {
        final Source source = new Source();
        final BaseMapLoader loader = new BaseMapLoader(source);
        final String[] status = new String[1];

        SwingUtilities.invokeAndWait(() -> {
            loader.setOnStatus(message -> status[0] = message);
            loader.viewChanged(BoundingBox.of(0, 0, 15, 15));
        });

        assertEquals(List.of(), source.asked);
        assertTrue(status[0].contains("World outline"), status[0]);
        // Between the two ceilings nothing loads by itself but the button
        // still works, and saying only "zoom in" reads as though it does not.
        assertTrue(status[0].contains("Load detail"), status[0]);
    }

    @Test
    void panningWiderThanOverpassWillServeSaysSoInstead() throws Exception {
        final BaseMapLoader loader = new BaseMapLoader(new Source());
        final String[] status = new String[1];

        SwingUtilities.invokeAndWait(() -> {
            loader.setOnStatus(message -> status[0] = message);
            loader.viewChanged(BoundingBox.of(0, 0, 40, 40));
        });

        assertTrue(status[0].contains("20°"), status[0]);
        assertFalse(status[0].contains("Load detail"), status[0]);
    }

    // ---- what is already on disk --------------------------------------------

    /** A source that has something stored and records whether it was asked. */
    private static final class Stored implements OsmSource {
        final List<BoundingBox> fetched = Collections.synchronizedList(new ArrayList<>());
        BoundingBox held;

        Stored(BoundingBox held) { this.held = held; }

        @Override
        public Cached cachedCovering(BoundingBox bbox, List<FeatureKind> kinds) {
            if (held == null || !held.contains(bbox)) return null;
            return new Cached(held, List.of(
                    new Feature(FeatureKind.COASTLINE,
                            List.of(new double[]{held.south(), held.west()},
                                    new double[]{held.north(), held.east()}),
                            Map.of("natural", "coastline"))));
        }

        @Override
        public List<Feature> fetch(BoundingBox bbox, List<FeatureKind> kinds) {
            fetched.add(bbox);
            return List.of(new Feature(FeatureKind.COASTLINE,
                    List.of(new double[]{bbox.south(), bbox.west()},
                            new double[]{bbox.north(), bbox.east()}),
                    Map.of("natural", "coastline")));
        }

        @Override
        public String description() { return "a test source"; }
    }

    /**
     * The reported case: restart, and the detail a previous session paid for
     * is on the map without anyone pressing anything.
     */
    @Test
    void detailAlreadyOnDiskIsShownAtStartup() throws Exception {
        final Stored source = new Stored(BoundingBox.of(50, -8, 58, 2));
        final BaseMapLoader loader = new BaseMapLoader(source);
        final BoundingBox view = BoundingBox.of(53, -4, 54, -3);

        awaitLoad(loader, () ->
                loader.restoreFromCache(view, List.of(FeatureKind.COASTLINE)));

        assertTrue(loader.hasDetailedShape(), "the stored detail is on the map");
        assertEquals(List.of(), source.fetched, "and nothing was downloaded");
        assertFalse(loader.showsBundledOutline(), "the outline stands aside for it");
    }

    @Test
    void startupIsSilentWhenThereIsNothingStored() throws Exception {
        final Stored source = new Stored(null);
        final BaseMapLoader loader = new BaseMapLoader(source);
        final boolean[] told = {false};

        SwingUtilities.invokeAndWait(() -> {
            loader.setOnLoaded(f -> told[0] = true);
            loader.restoreFromCache(BoundingBox.of(53, -4, 54, -3),
                                    List.of(FeatureKind.COASTLINE));
        });
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> { });

        assertFalse(told[0], "nothing to say, so nothing said");
        assertEquals(List.of(), source.fetched);
        assertTrue(loader.showsBundledOutline());
    }

    /**
     * And pressing the button over ground already held costs nothing either.
     * The ordinary cache is keyed on the exact request, which a restart never
     * reproduces.
     */
    @Test
    void loadDetailReusesWhatIsAlreadyHeldRatherThanFetching() throws Exception {
        final Stored source = new Stored(BoundingBox.of(50, -8, 58, 2));
        final BaseMapLoader loader = new BaseMapLoader(source);
        final BoundingBox view = BoundingBox.of(53, -4, 54, -3);

        SwingUtilities.invokeAndWait(() -> loader.viewChanged(view));
        final String status = awaitLoad(loader, () ->
                loader.loadNow(view, List.of(FeatureKind.COASTLINE)));

        assertEquals(List.of(), source.fetched, "nothing downloaded");
        assertTrue(status.contains("cache"), status);
        assertTrue(loader.hasDetailedShape());
    }

    /** The area recorded is the one actually held, not the one asked for. */
    @Test
    void reusingAWiderExtractRecordsTheWiderArea() throws Exception {
        final BoundingBox wide = BoundingBox.of(50, -8, 58, 2);
        final Stored source = new Stored(wide);
        final BaseMapLoader loader = new BaseMapLoader(source);
        final BoundingBox view = BoundingBox.of(53, -4, 54, -3);

        SwingUtilities.invokeAndWait(() -> loader.viewChanged(view));
        awaitLoad(loader, () -> loader.loadNow(view, List.of(FeatureKind.COASTLINE)));

        // Panning within what is held must not put the coarse outline back.
        SwingUtilities.invokeAndWait(() ->
                loader.viewChanged(BoundingBox.of(51, -7, 52, -6)));
        assertFalse(loader.showsBundledOutline(),
                    "still inside the ground that was reused");
    }

    @Test
    void whatIsHeldIsNotUsedWhenItDoesNotCoverTheView() throws Exception {
        final Stored source = new Stored(BoundingBox.of(50, -8, 51, -7));
        final BaseMapLoader loader = new BaseMapLoader(source);
        final BoundingBox view = BoundingBox.of(53, -4, 54, -3);

        SwingUtilities.invokeAndWait(() -> loader.viewChanged(view));
        awaitLoad(loader, () -> loader.loadNow(view, List.of(FeatureKind.COASTLINE)));

        assertEquals(1, source.fetched.size(), "so it had to be downloaded");
    }
}
