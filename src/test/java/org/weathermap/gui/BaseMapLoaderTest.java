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
        assertTrue(has(loader.features(), FeatureKind.PLACE));
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

    /** Dropping the margin cannot rescue a view that is itself too wide. */
    @Test
    void aViewBeyondTheLimitIsStillRefused() throws Exception {
        final Boxes source = new Boxes();
        final BaseMapLoader loader = new BaseMapLoader(source);
        final String[] status = new String[1];

        SwingUtilities.invokeAndWait(() -> {
            loader.setOnStatus(message -> status[0] = message);
            loader.loadNow(BoundingBox.of(0, 0, 25, 25), List.of(FeatureKind.COASTLINE));
        });

        assertEquals(List.of(), source.asked);
        assertTrue(status[0].contains("25°"), status[0]);
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
    void anAreaTooLargeForOverpassIsRefusedWithTheSpanInTheMessage() throws Exception {
        final Source source = new Source();
        final BaseMapLoader loader = new BaseMapLoader(source);
        final String[] status = new String[1];

        SwingUtilities.invokeAndWait(() -> {
            loader.setOnStatus(message -> status[0] = message);
            loader.loadNow(BoundingBox.of(0, 0, 40, 40), List.of(FeatureKind.COASTLINE));
        });

        assertEquals(List.of(), source.asked, "nothing asked of a service that would refuse");
        assertTrue(status[0].contains("40°"), status[0]);
        assertFalse(loader.hasDetailedShape());
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
}
