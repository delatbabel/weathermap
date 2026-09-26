package org.weathermap.gui;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.RenderSpec;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The blue overlay that shows what has been chosen, on a view and a selection
 * that wrap independently of each other.
 */
class MapPanelOverlayTest {

    private static final int W = 400;
    private static final int H = 200;

    /** The selection's own colour, which nothing else on the panel uses. */
    private static final int EDGE = new Color(20, 80, 170).getRGB();

    /**
     * Which columns of the painted panel carry any of the overlay.
     *
     * <p>On the EDT, and in stages, for two reasons. {@code setSize} posts a
     * resize event that marks the render stale, so painting from the test
     * thread raced it and about one run in five came out blank. And the base
     * map is now drawn on a worker: the first paint only starts it, so the
     * frame has to be waited for before there is anything to assert on.</p>
     */
    private static boolean[] overlayColumns(BoundingBox view, BoundingBox selection)
            throws Exception {
        final MapPanel panel = new MapPanel(new RenderSpec(), view);
        final BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            panel.setSize(W, H);
            panel.setSelection(selection);
        });
        awaitFrame(panel, image);
        paintOnce(panel, image);

        final boolean[] columns = new boolean[W];
        for (int x = 0; x < W; x++) {
            for (int y = 0; y < H; y++) {
                if (image.getRGB(x, y) == EDGE) {
                    columns[x] = true;
                    break;
                }
            }
        }
        return columns;
    }

    private static void paintOnce(MapPanel panel, BufferedImage image) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final Graphics2D g = image.createGraphics();
            try {
                panel.paint(g);
            }
            finally {
                g.dispose();
            }
        });
    }

    /** Paints until the worker has produced a frame for the current view. */
    private static void awaitFrame(MapPanel panel, BufferedImage image) throws Exception {
        final long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            // Painting is what starts the render, so this both kicks it off
            // and picks up the result.
            paintOnce(panel, image);
            final boolean[] ready = {false};
            javax.swing.SwingUtilities.invokeAndWait(() -> ready[0] = panel.hasCurrentFrame());
            if (ready[0]) return;
            Thread.sleep(20);
        }
        throw new AssertionError("the base map was never drawn");
    }

    private static int first(boolean[] columns) {
        for (int x = 0; x < columns.length; x++) if (columns[x]) return x;
        return -1;
    }

    private static int last(boolean[] columns) {
        for (int x = columns.length - 1; x >= 0; x--) if (columns[x]) return x;
        return -1;
    }

    /**
     * A selection across the seam sits where the ground it covers sits: in the
     * middle of a view centred on the antimeridian, not split to the two edges.
     */
    @Test
    void aSelectionAcrossTheSeamIsDrawnWhereItActuallyIs() throws Exception {
        final boolean[] columns = overlayColumns(BoundingBox.of(-30, 150, 30, -150),
                                                 BoundingBox.of(-10, 170, 10, -170));
        final int left = first(columns);
        final int right = last(columns);

        assertTrue(left > 0, "not against the left edge");
        assertTrue(right < W - 1, "nor the right one");
        assertEquals(W / 2.0, (left + right) / 2.0, 6,
                     "centred on the view, which is centred on the seam");
        // The view carries a quarter of margin, so twenty degrees of a
        // seventy-five degree view is a bit over a quarter of the panel.
        assertEquals(20.0 / 75 * W, right - left, 6);
    }

    /**
     * A selection straddling the edge of the view appears at both ends of the
     * panel, because those two ends are the same meridian.
     */
    @Test
    void aSelectionStraddlingTheViewEdgeAppearsAtBothEnds() throws Exception {
        final boolean[] columns = overlayColumns(BoundingBox.of(-30, -180, 30, 180),
                                                 BoundingBox.of(-10, 170, 10, -170));

        assertTrue(columns[0], "the part that has come round past the right edge");
        assertTrue(columns[W - 1], "and the part before it");
        assertFalse(columns[W / 2], "with nothing drawn between them");
    }

    @Test
    void anOrdinarySelectionIsStillDrawnOnce() throws Exception {
        final boolean[] columns = overlayColumns(BoundingBox.of(40, -30, 70, 20),
                                                 BoundingBox.of(49.5, -11, 61, 2));
        final int left = first(columns);
        final int right = last(columns);

        assertTrue(left > 0 && right < W - 1, "one rectangle, clear of both edges");
        for (int x = left; x <= right; x++) {
            assertTrue(columns[x], "and it is one run, not two: gap at " + x);
        }
    }
}
