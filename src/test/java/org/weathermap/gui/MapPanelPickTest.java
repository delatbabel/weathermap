package org.weathermap.gui;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.RenderSpec;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choosing where to read a tide: the click that picks a point, and the things
 * that must keep working while the map waits for it.
 */
class MapPanelPickTest {

    private static final int W = 400;
    private static final int H = 200;

    /** A panel sized and laid out on the EDT, ready to be clicked. */
    private static MapPanel panel(BoundingBox view) throws Exception {
        final MapPanel[] made = new MapPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            final MapPanel p = new MapPanel(new RenderSpec(), view);
            p.setSize(W, H);
            made[0] = p;
        });
        // The resize event has to be drained before anything paints; see
        // MapPanelOverlayTest for what happens when it is not.
        SwingUtilities.invokeAndWait(() -> { });
        return made[0];
    }

    private static void click(Component c, int x, int y, int button) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.event.MouseListener l : c.getMouseListeners()) {
                l.mouseClicked(new MouseEvent(c, MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(), 0, x, y, 1, false, button));
            }
        });
    }

    private static void press(Component c, int x, int y, int button) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.event.MouseListener l : c.getMouseListeners()) {
                l.mousePressed(new MouseEvent(c, MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK,
                        x, y, 1, false, button));
            }
        });
    }

    private static void release(Component c, int x, int y, int button) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.event.MouseListener l : c.getMouseListeners()) {
                l.mouseReleased(new MouseEvent(c, MouseEvent.MOUSE_RELEASED,
                        System.currentTimeMillis(), 0, x, y, 1, false, button));
            }
        });
    }

    // ---- the pick -------------------------------------------------------------

    @Test
    void aClickWhilePickingGivesThePositionUnderIt() throws Exception {
        final MapPanel map = panel(BoundingBox.of(-10, 100, 10, 120));
        final List<double[]> picked = new ArrayList<>();

        SwingUtilities.invokeAndWait(() -> map.pickPoint(picked::add));
        assertTrue(map.isPicking());

        click(map, W / 2, H / 2, MouseEvent.BUTTON1);

        assertEquals(1, picked.size());
        // The middle of the panel is the middle of the view, whatever margin
        // MapView framed it with.
        final double[] centre = map.viewBounds() == null ? null : new double[]{
                map.viewBounds().centreLat(), map.viewBounds().centreLon()};
        assertEquals(centre[0], picked.get(0)[0], 0.2);
        assertEquals(centre[1], picked.get(0)[1], 0.2);
    }

    /**
     * Disarmed before the callback runs, not after. The callback puts a modal
     * dialog on screen, and a pick still armed underneath it takes the click
     * that dismisses the dialog as a second point.
     */
    @Test
    void oneClickPicksOnce() throws Exception {
        final MapPanel map = panel(BoundingBox.of(-10, 100, 10, 120));
        final List<double[]> picked = new ArrayList<>();

        SwingUtilities.invokeAndWait(() -> map.pickPoint(picked::add));
        click(map, 100, 100, MouseEvent.BUTTON1);
        click(map, 200, 100, MouseEvent.BUTTON1);

        assertEquals(1, picked.size());
        assertFalse(map.isPicking());
    }

    @Test
    void escapeAbandonsThePickAndSaysSo() throws Exception {
        final MapPanel map = panel(BoundingBox.of(-10, 100, 10, 120));
        final List<double[]> picked = new ArrayList<>();
        final boolean[] told = {false};

        SwingUtilities.invokeAndWait(() -> {
            map.setPickCancelledListener(() -> told[0] = true);
            map.pickPoint(picked::add);
            map.getActionMap().get("cancelPick").actionPerformed(
                    new java.awt.event.ActionEvent(map, 0, "cancelPick"));
        });

        assertFalse(map.isPicking());
        assertTrue(told[0], "the status line has to stop asking for a click");

        click(map, 100, 100, MouseEvent.BUTTON1);
        assertEquals(0, picked.size(), "and the click goes back to being a click");
    }

    @Test
    void escapeWithNoPickArmedDoesNothing() throws Exception {
        final MapPanel map = panel(BoundingBox.of(-10, 100, 10, 120));
        final boolean[] told = {false};

        SwingUtilities.invokeAndWait(() -> {
            map.setPickCancelledListener(() -> told[0] = true);
            map.getActionMap().get("cancelPick").actionPerformed(
                    new java.awt.event.ActionEvent(map, 0, "cancelPick"));
        });
        assertFalse(told[0]);
    }

    // ---- what must keep working -----------------------------------------------

    /**
     * Scrolling to the place is most of what choosing a point consists of, so
     * a drag has to pan even in SELECT mode, where it would otherwise rubber-band
     * out a download rectangle in answer to a question about where.
     */
    @Test
    void draggingWhilePickingPansEvenInSelectMode() throws Exception {
        final MapPanel map = panel(BoundingBox.of(-10, 100, 10, 120));
        SwingUtilities.invokeAndWait(() -> {
            map.setMode(MapPanel.Mode.SELECT);
            map.pickPoint(at -> { });
        });
        final BoundingBox before = map.selection();
        final double centreBefore = map.viewBounds().centreLon();

        press(map, 300, 100, MouseEvent.BUTTON1);
        release(map, 100, 100, MouseEvent.BUTTON1);

        assertEquals(before, map.selection(), "the chosen area is untouched");
        assertTrue(map.viewBounds().centreLon() > centreBefore, "and the view moved east");
    }

    @Test
    void aRightClickDoesNotPick() throws Exception {
        final MapPanel map = panel(BoundingBox.of(-10, 100, 10, 120));
        final List<double[]> picked = new ArrayList<>();

        SwingUtilities.invokeAndWait(() -> map.pickPoint(picked::add));
        click(map, 100, 100, MouseEvent.BUTTON3);

        assertEquals(0, picked.size());
        assertTrue(map.isPicking(), "still waiting for a left click");
    }

    /**
     * A point across the antimeridian comes back in range, not as 190 degrees:
     * everything downstream, including the Storm Glass request, assumes
     * -180..180.
     */
    @Test
    void aPickAcrossTheSeamIsNormalised() throws Exception {
        final MapPanel map = panel(BoundingBox.of(-10, 170, 10, -170));
        final List<double[]> picked = new ArrayList<>();

        SwingUtilities.invokeAndWait(() -> map.pickPoint(picked::add));
        click(map, W - 20, H / 2, MouseEvent.BUTTON1);

        assertEquals(1, picked.size());
        final double lon = picked.get(0)[1];
        assertTrue(lon >= -180 && lon <= 180, "in range: " + lon);
        assertTrue(lon < 0, "the eastern edge of this view is west longitude: " + lon);
    }
}
