package org.weathermap.gui;

import org.weathermap.model.BoundingBox;
import org.weathermap.osm.Feature;
import org.weathermap.osm.FeatureKind;
import org.weathermap.osm.OsmSource;
import org.weathermap.osm.OverpassClient;
import org.weathermap.osm.WorldBaseMap;
import org.weathermap.osm.WorldGazetteer;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps the selection map supplied with OSM detail as the user pans and zooms,
 * without hammering Overpass.
 *
 * <h2>The constraint this is built around</h2>
 *
 * <p>Overpass is a shared public service and a single query for a few degrees
 * takes tens of seconds. Fetching on every pan would be both unusable and
 * abusive. So:</p>
 *
 * <ul>
 *   <li><b>Nothing is fetched while the view is moving.</b> A request is
 *       scheduled {@value #DEBOUNCE_MS} ms after the last movement and cancelled
 *       if another arrives.</li>
 *   <li><b>A generous margin is fetched</b> around the view, so a small pan is
 *       served from what is already in hand.</li>
 *   <li><b>Nothing is fetched at all above {@value #MAX_DETAIL_SPAN} degrees</b>,
 *       where the query would be refused and the bundled
 *       {@link WorldBaseMap} outline is the right level of detail anyway.</li>
 *   <li>Results are <b>cached on disk for four weeks</b> by
 *       {@link org.weathermap.osm.OverpassClient}, so revisiting a region costs
 *       nothing.</li>
 * </ul>
 *
 * <p>The world outline is always present underneath, so the map is never blank
 * and detail arrives as an improvement rather than as the difference between
 * something and nothing.</p>
 */
public final class BaseMapLoader {

    private static final Logger LOG = Logger.getLogger(BaseMapLoader.class.getName());

    /** Quiet period after the last pan or zoom before a query is sent. */
    private static final int DEBOUNCE_MS = 600;

    /** Above this view span, only the bundled world outline is used. */
    public static final double MAX_DETAIL_SPAN = 8.0;

    /** How much larger than the view to fetch, so small pans need no refetch. */
    private static final double MARGIN_FACTOR = 0.35;

    /**
     * Place names only - <b>not</b> coastline or boundaries.
     *
     * <p>This is the change that makes the interactive map workable. A coastline
     * query for four degrees of Scotland returns seventy megabytes, because the
     * recursion that gives ways their coordinates pulls every node of every
     * full-resolution coastline; asking for it repeatedly as the user pans is
     * what got this client answered with 429s.</p>
     *
     * <p>And it buys nothing here. {@link WorldBaseMap} already supplies the
     * shape of the land, which is what orients someone choosing a rectangle;
     * what it lacks is names. Full-resolution coastline belongs to the
     * composited output, where it is fetched once for a chosen area and cached
     * for four weeks - see {@link org.weathermap.MapService}.</p>
     */
    private static final List<FeatureKind> KINDS = List.of(FeatureKind.PLACE);

    private final OsmSource source;
    private final Timer debounce;

    /**
     * One thread, so queries can never overlap. Overpass counts concurrent
     * requests per client and refuses the second one.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "basemap-loader");
        t.setDaemon(true);
        return t;
    });

    private Consumer<List<Feature>> onLoaded = features -> { };
    private Consumer<String> onStatus = message -> { };

    /** The area the detail in hand covers, or {@code null} when there is none. */
    private BoundingBox loadedFor;
    private List<Feature> detail = List.of();

    /**
     * True when {@link #detail} carries the shape of the land, not only names.
     *
     * <p>Which is half of deciding whether the bundled outline is drawn
     * underneath it; the other half is {@link #showsBundledOutline}.</p>
     */
    private boolean detailHasShape;

    /** Where the user is looking, so the outline can be put back when they leave. */
    private BoundingBox view;

    /** What {@link #features()} last decided, so a change can be announced. */
    private boolean bundledShowing = true;

    private BoundingBox pending;
    private List<FeatureKind> pendingKinds = KINDS;
    private long generation;

    public BaseMapLoader(OsmSource source) {
        this.source = source;
        this.debounce = new Timer(DEBOUNCE_MS, e -> fetchNow());
        this.debounce.setRepeats(false);
    }

    /** Called on the EDT whenever more detail has arrived. */
    public void setOnLoaded(Consumer<List<Feature>> listener) {
        this.onLoaded = listener;
    }

    /** Called on the EDT with a short progress or explanation message. */
    public void setOnStatus(Consumer<String> listener) {
        this.onStatus = listener;
    }

    /**
     * Everything the selection map should draw.
     *
     * <p>Place names layer over the bundled outline happily, because the
     * outline has none. A real coastline does not: two coastlines a
     * kilometre apart are not a richer map, and Natural Earth 1:110m is about
     * that good, which at four degrees across a window is tens of pixels of
     * visible disagreement.</p>
     *
     * <p>So the outline steps aside - but only while the view is inside the
     * area that was loaded. Dropping it outright was worse than the problem
     * it solved: loading detail for one harbour and then zooming out left
     * that harbour drawn on an empty world. Which way round to go is decided
     * by {@link #showsBundledOutline}.</p>
     */
    public List<Feature> features() {
        final List<Feature> out = new ArrayList<>();
        if (showsBundledOutline()) out.addAll(WorldBaseMap.features());

        // The same names the chart puts on, from the same bundle. Without
        // these the selection map had no names at all above eight degrees -
        // no seas, no countries, no cities - while a chart of the same area
        // had all three, so the map someone chooses an area on was emptier
        // than the thing it is for.
        out.addAll(WorldGazetteer.of(FeatureKind.MARINE));
        out.addAll(WorldGazetteer.of(FeatureKind.COUNTRY));

        // Bundled cities only where OSM has supplied none. Mixing the two
        // puts two dots on some of them and ranks them by two different
        // meanings of "important" - the same reason MapService picks one set
        // or the other rather than both.
        if (!hasOsmPlaces()) out.addAll(WorldGazetteer.of(FeatureKind.PLACE));

        out.addAll(detail);
        return out;
    }

    private boolean hasOsmPlaces() {
        for (Feature f : detail) {
            if (f.kind() == FeatureKind.PLACE) return true;
        }
        return false;
    }

    /**
     * Whether the coarse world outline belongs on screen.
     *
     * <p>It does unless real geometry has been loaded <em>and</em> covers
     * everything currently visible. Zoomed out past the loaded area the two
     * are both drawn, and the doubling that would be obvious close up is by
     * then well under a pixel - the same fact that makes the outline good
     * enough at that scale in the first place.</p>
     */
    public boolean showsBundledOutline() {
        if (!detailHasShape || loadedFor == null) return true;
        return view == null || !loadedFor.contains(view);
    }

    /** True when real OSM geometry has been loaded, wherever the view is now. */
    public boolean hasDetailedShape() { return detailHasShape; }

    /**
     * Tells the loader where the user is now looking.
     *
     * <p>Cheap and idempotent: call it on every pan and zoom. It decides whether
     * anything actually needs fetching.</p>
     */
    public void viewChanged(BoundingBox view) {
        this.view = view;
        // Moving out of - or back into - the detailed area changes what should
        // be drawn even when nothing is fetched, and nothing else will notice:
        // the paths below return without loading anything.
        if (bundledShowing != showsBundledOutline()) {
            bundledShowing = showsBundledOutline();
            onLoaded.accept(features());
        }
        if (view.widthDegrees() > MAX_DETAIL_SPAN) {
            debounce.stop();
            pending = null;
            // Which of the two ceilings applies matters to the reader. Between
            // them, nothing loads by itself but the button still works, and
            // saying only "zoom in below 8°" reads as though it does not.
            onStatus.accept(OverpassClient.isTooLarge(view)
                    ? String.format("World outline - zoom in below %.0f° to load map detail",
                                    OverpassClient.MAX_SERVABLE_SPAN)
                    : String.format("World outline - zoom in below %.0f° for place names, "
                                    + "or press Load detail for this area", MAX_DETAIL_SPAN));
            return;
        }
        if (covers(loadedFor, view)) return;          // already in hand

        pending = withMargin(view);
        pendingKinds = KINDS;
        debounce.restart();
        onStatus.accept("Detail will load when you stop moving…");
    }

    /**
     * Fetches everything a chart of this view would be drawn from, now.
     *
     * <p><b>This is not the automatic path with the waiting removed.</b> That
     * one asks for place names only, and must: a coastline query repeated on
     * every pan is what got this client answered with 429s. A button press is
     * a different thing - it happens once, when someone has decided they want
     * it - so it asks for what the chart asks for, and the selection map then
     * shows the geography the chart would show, minus the weather.</p>
     *
     * <p>Which kinds those are comes from
     * {@link org.weathermap.MapService#featureKindsFor}, so the two cannot
     * drift. They already had: the button fetched names while its tooltip
     * promised a coastline, and pressing it looked like it did nothing.</p>
     *
     * @param kinds what to ask for, usually the chart's own kinds
     */
    public void loadNow(BoundingBox view, List<FeatureKind> kinds) {
        if (kinds.isEmpty()) {
            onStatus.accept("No map layers are switched on - nothing to load");
            return;
        }

        // The margin is the first thing to go. It exists so that a small pan
        // afterwards needs no refetch, which is worth having and is not worth
        // failing the whole request for - and adding 35% to each side of a
        // fifteen degree view asks for twenty-five and a half, which Overpass
        // will not serve. Checking the view and then sending the margined box
        // is what let that through: the guard has to be on the box that is
        // actually asked for.
        BoundingBox target = withMargin(view);
        if (OverpassClient.isTooLarge(target)) target = view;

        if (OverpassClient.isTooLarge(target)) {
            useBundledMap(view);
            return;
        }
        pending = target;
        pendingKinds = List.copyOf(kinds);
        debounce.stop();
        fetchNow();
    }

    /**
     * Falls back to the bundled world map, which is what the chart does.
     *
     * <p>Above twenty degrees Overpass will not answer at all, and this used
     * to refuse - leaving the button doing nothing, with nothing in the log,
     * on exactly the wide areas a saved profile recalls. Meanwhile
     * <b>Download and composite</b> quietly fell back to the bundle and drew
     * a complete chart, so the two disagreed about the same area and the map
     * was the poorer of them.</p>
     *
     * <p>Now it does the same thing, and says so in the same words. Whatever
     * detail was loaded for somewhere else is dropped: the request was for
     * detail <em>here</em>, and here the bundle is the answer.</p>
     */
    private void useBundledMap(BoundingBox view) {
        LOG.info(() -> String.format(
                "Load detail: %.1f deg is wider than the %.0f deg Overpass will serve; "
                + "using the bundled world map",
                Math.max(view.widthDegrees(), view.heightDegrees()),
                OverpassClient.MAX_SERVABLE_SPAN));

        detail = List.of();
        loadedFor = null;
        detailHasShape = false;
        bundledShowing = true;
        pending = null;
        debounce.stop();
        onStatus.accept(String.format(
                "Area wider than %.0f° - using the bundled world map",
                OverpassClient.MAX_SERVABLE_SPAN));
        onLoaded.accept(features());
    }

    private void fetchNow() {
        final BoundingBox target = pending;
        final List<FeatureKind> kinds = pendingKinds;
        if (target == null) return;
        pending = null;

        final long mine = ++generation;
        LOG.info(() -> String.format(
                "Load detail: %.1f x %.1f deg, %s", target.widthDegrees(),
                target.heightDegrees(), kinds));
        onStatus.accept("Loading map detail from " + source.description() + "…");

        worker.submit(() -> {
            try {
                final List<Feature> fetched = source.fetch(target, kinds);
                SwingUtilities.invokeLater(() -> {
                    // A later view change has already superseded this request;
                    // dropping it keeps the map consistent with the viewport.
                    if (mine != generation) return;
                    detail = fetched;
                    loadedFor = target;
                    detailHasShape = hasShape(fetched);
                    bundledShowing = showsBundledOutline();
                    LOG.info(() -> describe(fetched));
                    onStatus.accept(describe(fetched));
                    onLoaded.accept(features());
                });
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            catch (Exception e) {
                LOG.log(Level.FINE, "Map detail unavailable", e);
                SwingUtilities.invokeLater(() -> {
                    if (mine != generation) return;
                    // Overpass rate-limits and times out routinely. The world
                    // outline is still on screen, so this is a note, not a
                    // failure - saying so keeps the user from waiting for
                    // something that is not coming.
                    LOG.warning("Load detail failed: " + shortReason(e));
                    onStatus.accept("Map detail unavailable: " + shortReason(e)
                            + " - showing the world outline");
                });
            }
        });
    }

    /** True when what came back includes the shape of the land, not just names. */
    private static boolean hasShape(List<Feature> features) {
        for (Feature f : features) {
            if (f.kind() == FeatureKind.COASTLINE || f.kind() == FeatureKind.BOUNDARY) {
                return true;
            }
        }
        return false;
    }

    /**
     * What arrived, counted by kind.
     *
     * <p>Named rather than totalled, because "1,482 features loaded" does not
     * tell you whether the thing you pressed the button for is among them.</p>
     */
    private static String describe(List<Feature> features) {
        if (features.isEmpty()) return "Nothing mapped here";

        int coastline = 0;
        int boundaries = 0;
        int places = 0;
        for (Feature f : features) {
            switch (f.kind()) {
                case COASTLINE -> coastline++;
                case BOUNDARY -> boundaries++;
                case PLACE -> places++;
                default -> { }
            }
        }
        final List<String> parts = new ArrayList<>();
        if (coastline > 0) parts.add(coastline + " coastline");
        if (boundaries > 0) parts.add(boundaries + " boundary");
        if (places > 0) parts.add(places + " place name" + (places == 1 ? "" : "s"));
        return parts.isEmpty()
                ? features.size() + " features loaded"
                : "Loaded " + String.join(", ", parts);
    }

    private static String shortReason(Exception e) {
        if (e instanceof org.weathermap.util.Http.RateLimitedException) return e.getMessage();
        final String name = e.getClass().getSimpleName();
        return name.contains("Timeout") ? "timed out" : name;
    }

    /** True when {@code loaded} already contains all of {@code view}. */
    private static boolean covers(BoundingBox loaded, BoundingBox view) {
        // BoundingBox does the comparison rather than four inequalities here,
        // because west <= west is not the question once either box can run
        // through the antimeridian with its east edge the smaller number.
        return loaded != null && loaded.contains(view);
    }

    private static BoundingBox withMargin(BoundingBox view) {
        final double margin = Math.max(view.widthDegrees(), view.heightDegrees()) * MARGIN_FACTOR;
        return view.expanded(margin);
    }

    /** Stops the timer and the worker; call when the window closes. */
    public void dispose() {
        debounce.stop();
        worker.shutdownNow();
    }
}
