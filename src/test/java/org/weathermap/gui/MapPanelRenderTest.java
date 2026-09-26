package org.weathermap.gui;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.RenderSpec;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base map is drawn on a worker, not in {@code paintComponent}.
 *
 * <p>Thirteen degrees of loaded OSM coastline took over a hundred milliseconds
 * to composite, and it used to take it on the event thread - so every pan,
 * zoom and resize froze the window for that long. What is on screen may now be
 * one view behind for a moment, which is what these pin down.</p>
 */
class MapPanelRenderTest {

    private static final int W = 400;
    private static final int H = 200;

    private final BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);

    private MapPanel panel(BoundingBox view) throws Exception {
        final MapPanel[] made = new MapPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            final MapPanel p = new MapPanel(new RenderSpec(), view);
            p.setSize(W, H);
            made[0] = p;
        });
        SwingUtilities.invokeAndWait(() -> { });      // drain the resize event
        return made[0];
    }

    private void paint(MapPanel panel) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final Graphics2D g = canvas.createGraphics();
            try {
                panel.paint(g);
            }
            finally {
                g.dispose();
            }
        });
    }

    private boolean current(MapPanel panel) throws Exception {
        final boolean[] is = {false};
        SwingUtilities.invokeAndWait(() -> is[0] = panel.hasCurrentFrame());
        return is[0];
    }

    private void awaitFrame(MapPanel panel) throws Exception {
        final long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            paint(panel);                              // painting starts the render
            if (current(panel)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("the base map was never drawn");
    }

    @Test
    void theFirstPaintOnlyStartsTheRender() throws Exception {
        final MapPanel map = panel(BoundingBox.of(49.5, -11, 61, 2));
        paint(map);
        // Whether it has finished by now is a race, but it must not have been
        // done synchronously inside paint - so there is nothing to assert
        // except that asking is safe and that it arrives.
        awaitFrame(map);
        assertTrue(current(map));
    }

    @Test
    void movingTheViewMakesTheFrameStaleAndThenCurrentAgain() throws Exception {
        final MapPanel map = panel(BoundingBox.of(49.5, -11, 61, 2));
        awaitFrame(map);

        SwingUtilities.invokeAndWait(() -> map.showArea(BoundingBox.of(40, 0, 50, 15)));
        assertFalse(current(map), "the frame on screen is for where we were");

        // And it still paints - the last frame stretched into place - rather
        // than blanking, which is the whole point of keeping it.
        paint(map);
        awaitFrame(map);
        assertTrue(current(map));
    }

    @Test
    void newFeaturesMakeTheFrameStale() throws Exception {
        final MapPanel map = panel(BoundingBox.of(49.5, -11, 61, 2));
        awaitFrame(map);

        SwingUtilities.invokeAndWait(() ->
                map.setFeatures(org.weathermap.osm.WorldGazetteer.of(
                        org.weathermap.osm.FeatureKind.PLACE)));
        assertFalse(current(map));
        awaitFrame(map);
    }

    /**
     * Several moves in quick succession must settle on the last one. A queue
     * of superseded frames arriving one after another would have the map walk
     * backwards through where it had been.
     */
    @Test
    void aBurstOfMovementSettlesOnWhereItEnded() throws Exception {
        final MapPanel map = panel(BoundingBox.of(49.5, -11, 61, 2));
        awaitFrame(map);

        final BoundingBox last = BoundingBox.of(30, 100, 40, 115);
        SwingUtilities.invokeAndWait(() -> {
            map.showArea(BoundingBox.of(0, 0, 10, 10));
            map.showArea(BoundingBox.of(10, 20, 20, 30));
            map.showArea(last);
        });
        awaitFrame(map);

        assertTrue(current(map));
        // Settled on the last one asked for, framed the way MapView frames it.
        final BoundingBox showing = map.viewBounds();
        assertTrue(Math.abs(showing.centreLon() - last.centreLon()) < 1,
                   "centred on the last move, not an earlier one: " + showing);
    }

    @Test
    void disposingStopsTheRenderThread() throws Exception {
        final MapPanel map = panel(BoundingBox.of(49.5, -11, 61, 2));
        awaitFrame(map);
        SwingUtilities.invokeAndWait(map::dispose);
        // Painting after disposal must not throw; there is simply no new frame.
        paint(map);
    }
}
