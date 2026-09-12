package org.weathermap.gui;

import org.weathermap.model.BoundingBox;
import org.weathermap.osm.Feature;
import org.weathermap.osm.FeatureKind;
import org.weathermap.osm.OsmSource;
import org.weathermap.osm.WorldBaseMap;

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

    private BoundingBox pending;
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

    /** The world outline plus whatever detail has been loaded, ready to draw. */
    public List<Feature> features() {
        final List<Feature> out = new ArrayList<>(WorldBaseMap.features());
        out.addAll(detail);
        return out;
    }

    /**
     * Tells the loader where the user is now looking.
     *
     * <p>Cheap and idempotent: call it on every pan and zoom. It decides whether
     * anything actually needs fetching.</p>
     */
    public void viewChanged(BoundingBox view) {
        if (view.widthDegrees() > MAX_DETAIL_SPAN) {
            debounce.stop();
            pending = null;
            onStatus.accept(String.format(
                    "World outline - zoom in below %.0f° for place names", MAX_DETAIL_SPAN));
            return;
        }
        if (covers(loadedFor, view)) return;          // already in hand

        pending = withMargin(view);
        debounce.restart();
        onStatus.accept("Detail will load when you stop moving…");
    }

    /** Skips the quiet period - for an explicit "load detail here" action. */
    public void loadNow(BoundingBox view) {
        if (view.widthDegrees() > MAX_DETAIL_SPAN) {
            onStatus.accept(String.format(
                    "Zoom in below %.0f° before loading detail", MAX_DETAIL_SPAN));
            return;
        }
        pending = withMargin(view);
        debounce.stop();
        fetchNow();
    }

    private void fetchNow() {
        final BoundingBox target = pending;
        if (target == null) return;
        pending = null;

        final long mine = ++generation;
        onStatus.accept("Loading map detail from " + source.description() + "…");

        worker.submit(() -> {
            try {
                final List<Feature> fetched = source.fetch(target, KINDS);
                SwingUtilities.invokeLater(() -> {
                    // A later view change has already superseded this request;
                    // dropping it keeps the map consistent with the viewport.
                    if (mine != generation) return;
                    detail = fetched;
                    loadedFor = target;
                    onStatus.accept(fetched.isEmpty()
                            ? "No named places here"
                            : fetched.size() + " place names loaded");
                    onLoaded.accept(features());
                });
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            catch (Exception e) {
                LOG.log(Level.FINE, "Place-name detail unavailable", e);
                SwingUtilities.invokeLater(() -> {
                    if (mine != generation) return;
                    // Overpass rate-limits and times out routinely. The world
                    // outline is still on screen, so this is a note, not a
                    // failure - saying so keeps the user from waiting for
                    // something that is not coming.
                    onStatus.accept("Place names unavailable: " + shortReason(e)
                            + " - showing the world outline");
                });
            }
        });
    }

    private static String shortReason(Exception e) {
        if (e instanceof org.weathermap.util.Http.RateLimitedException) return e.getMessage();
        final String name = e.getClass().getSimpleName();
        return name.contains("Timeout") ? "timed out" : name;
    }

    /** True when {@code loaded} already contains all of {@code view}. */
    private static boolean covers(BoundingBox loaded, BoundingBox view) {
        return loaded != null
                && loaded.south() <= view.south() && loaded.north() >= view.north()
                && loaded.west() <= view.west() && loaded.east() >= view.east();
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
