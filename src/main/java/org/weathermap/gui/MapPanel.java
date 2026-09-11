package org.weathermap.gui;

import org.weathermap.model.BoundingBox;
import org.weathermap.model.MapProjection;
import org.weathermap.model.RenderSpec;
import org.weathermap.osm.Feature;
import org.weathermap.render.Compositor;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

/**
 * The interactive base map: pan, zoom, and drag out the rectangle to download.
 *
 * <h2>What it shows</h2>
 *
 * <p><b>The base map only - never the weather.</b> Choosing an area means seeing
 * the coastline and the place names clearly, and a translucent temperature field
 * over them makes that harder, not easier. A composited result is shown here
 * too, but only after a download and only until the view moves; the moment the
 * user pans, zooms or selects, the panel goes back to the plain base map because
 * they have gone back to choosing.</p>
 *
 * <h2>Mouse</h2>
 *
 * <table border="1">
 *   <caption>Bindings</caption>
 *   <tr><td>Drag</td><td>Pan, or select in {@link Mode#SELECT}</td></tr>
 *   <tr><td>Shift-drag</td><td>Always selects, whatever the mode</td></tr>
 *   <tr><td>Right-drag</td><td>Always pans, whatever the mode</td></tr>
 *   <tr><td>Wheel</td><td>Zoom about the pointer</td></tr>
 *   <tr><td>Double-click</td><td>Zoom in one step, centred there</td></tr>
 * </table>
 *
 * <h2>Why the panning trick</h2>
 *
 * <p>Re-rendering fifteen thousand vector features takes long enough to be felt
 * at frame rate, so a drag does not re-render: the existing image is blitted at
 * an offset and the real render happens on release. That is what makes the drag
 * feel attached to the pointer rather than lagging behind it.</p>
 */
public final class MapPanel extends JPanel {

    /** What a plain drag does. */
    public enum Mode {
        /** Drag moves the map. Shift-drag still selects. */
        PAN,
        /** Drag draws the download rectangle. Right-drag still pans. */
        SELECT
    }

    private static final Color SELECTION_FILL = new Color(70, 130, 200, 55);
    private static final Color SELECTION_EDGE = new Color(20, 80, 170);
    private static final Color HINT = new Color(90, 90, 90);

    /** One wheel notch. Chosen to feel like a web map rather than a microscope. */
    private static final double ZOOM_STEP = 1.25;

    private final RenderSpec spec;
    private final MapView view;

    private List<Feature> features = List.of();
    private Mode mode = Mode.PAN;

    /** The current base-map render, and the view it was drawn for. */
    private BufferedImage baseImage;
    private MapProjection baseProjection;

    /** A composited weather map, shown until the view moves. */
    private BufferedImage resultImage;
    private MapProjection resultProjection;
    private boolean showingResult;

    /** The chosen download rectangle, drawn as an overlay. */
    private BoundingBox selection;

    private Consumer<BoundingBox> selectionListener = bbox -> { };
    private Consumer<BoundingBox> viewListener = bbox -> { };

    private Point dragStart;
    private Point dragNow;
    private boolean draggingSelection;

    public MapPanel(RenderSpec spec, BoundingBox initialView) {
        this.spec = spec;
        this.view = new MapView(initialView);
        this.selection = initialView;

        setBackground(new Color(236, 240, 244));
        setPreferredSize(new Dimension(900, 620));
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        setFocusable(true);

        final MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                dragStart = e.getPoint();
                dragNow = e.getPoint();
                draggingSelection = selectsWith(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart == null) return;
                dragNow = e.getPoint();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragStart == null) return;
                final Point from = dragStart;
                final Point to = e.getPoint();
                dragStart = null;
                dragNow = null;

                if (draggingSelection) finishSelection(from, to);
                else finishPan(from, to);
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                final double[] under = latLonAt(e.getPoint());
                if (under == null) return;
                view.zoomAbout(e.getWheelRotation() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP,
                               under[0], under[1]);
                viewMoved();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    final double[] under = latLonAt(e.getPoint());
                    if (under == null) return;
                    view.zoomAbout(1 / ZOOM_STEP, under[0], under[1]);
                    viewMoved();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);

        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                // The latitude span follows the aspect ratio, so a resize shows
                // more or less map and the render has to be redone.
                invalidateRender();
                repaint();
            }
        });
    }

    /** A plain drag selects in SELECT mode; shift always does; right never does. */
    private boolean selectsWith(MouseEvent e) {
        if (SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
            return false;
        }
        return e.isShiftDown() || mode == Mode.SELECT;
    }

    // ---- wiring ---------------------------------------------------------

    public void setSelectionListener(Consumer<BoundingBox> listener) {
        this.selectionListener = listener;
    }

    /** Told whenever the visible area changes, so detail can be loaded for it. */
    public void setViewListener(Consumer<BoundingBox> listener) {
        this.viewListener = listener;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        setCursor(Cursor.getPredefinedCursor(
                mode == Mode.SELECT ? Cursor.CROSSHAIR_CURSOR : Cursor.MOVE_CURSOR));
    }

    public Mode mode() { return mode; }

    /** Replaces the features drawn as the base map. */
    public void setFeatures(List<Feature> features) {
        this.features = features;
        invalidateRender();
        repaint();
    }

    /** Shows the selection rectangle without firing the listener. */
    public void setSelection(BoundingBox bbox) {
        this.selection = bbox;
        repaint();
    }

    public BoundingBox selection() { return selection; }

    /** The area currently on screen. */
    public BoundingBox viewBounds() {
        return view.bounds(Math.max(1, getWidth()), Math.max(1, getHeight()));
    }

    /** Moves the view to frame an area - used when the area is typed in. */
    public void showArea(BoundingBox bbox) {
        view.setTo(bbox);
        viewMoved();
    }

    /** Displays a composited map until the view next moves. */
    public void setResult(BufferedImage image, MapProjection projection) {
        this.resultImage = image;
        this.resultProjection = projection;
        this.showingResult = image != null;
        repaint();
    }

    /** True when a composited map is available to flip back to. */
    public boolean hasResult() { return resultImage != null; }

    public void setShowingResult(boolean showing) {
        this.showingResult = showing && resultImage != null;
        repaint();
    }

    public boolean isShowingResult() { return showingResult; }

    // ---- interaction ----------------------------------------------------

    private void viewMoved() {
        showingResult = false;              // back to choosing
        invalidateRender();
        repaint();
        viewListener.accept(viewBounds());
    }

    private void invalidateRender() {
        baseImage = null;
        baseProjection = null;
    }

    private void finishPan(Point from, Point to) {
        final int dx = to.x - from.x;
        final int dy = to.y - from.y;
        if (dx == 0 && dy == 0) return;

        final BoundingBox bounds = currentProjection().bounds();
        final double perPixelLon = bounds.widthDegrees() / Math.max(1, getWidth());
        final double perPixelLat = bounds.heightDegrees() / Math.max(1, getHeight());
        // Dragging right moves the map right, so the view moves west.
        view.panBy(dy * perPixelLat, -dx * perPixelLon);
        viewMoved();
    }

    private void finishSelection(Point from, Point to) {
        if (Math.abs(from.x - to.x) < 5 || Math.abs(from.y - to.y) < 5) return;

        final double[] a = latLonAt(from);
        final double[] b = latLonAt(to);
        if (a == null || b == null) return;

        try {
            final BoundingBox bbox = BoundingBox.of(
                    Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                    Math.max(a[0], b[0]), Math.max(a[1], b[1]));
            selection = bbox;
            showingResult = false;
            selectionListener.accept(bbox);
        }
        catch (IllegalArgumentException e) {
            // Too small, or dragged off the edge. Ignore rather than complain -
            // a stray drag is not an error worth a dialog.
        }
    }

    /** @return {@code {lat, lon}} under a panel point, or null before first render */
    private double[] latLonAt(Point p) {
        final MapProjection projection = currentProjection();
        if (projection == null) return null;
        return projection.toLatLon(p.x, p.y);
    }

    // ---- rendering ------------------------------------------------------

    /**
     * The projection for the current viewport, rendering the base map if it is
     * not already in hand.
     */
    private MapProjection currentProjection() {
        if (getWidth() <= 0 || getHeight() <= 0) return null;
        if (baseProjection == null) renderBase();
        return baseProjection;
    }

    /**
     * Draws the base map at exactly panel size.
     *
     * <p>Panel size rather than {@link RenderSpec}'s output size, so that panel
     * pixels and image pixels are the same thing - which is what makes the hit
     * testing for pan, zoom and selection exact instead of approximately right.</p>
     */
    private void renderBase() {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        final BoundingBox bounds = view.bounds(w, h);
        final RenderSpec base = spec.copy();
        base.setMaxSize(w, h);
        base.setEnabled(RenderSpec.LayerKind.GRIB, false);
        base.setEnabled(RenderSpec.LayerKind.GRATICULE, true);
        base.setEnabled(RenderSpec.LayerKind.ANNOTATION, false);   // no legend to show

        baseProjection = base.projectionFor(bounds);
        baseImage = new Compositor(base).render(bounds, features, List.of(), "");
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                               RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                               RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            if (showingResult && resultImage != null) {
                paintResult(g);
                return;
            }

            if (baseProjection == null) renderBase();
            if (baseImage == null) return;

            // A pan in progress is shown by offsetting the last render; the
            // real one happens on release.
            int ox = 0;
            int oy = 0;
            if (dragStart != null && dragNow != null && !draggingSelection) {
                ox = dragNow.x - dragStart.x;
                oy = dragNow.y - dragStart.y;
            }
            g.drawImage(baseImage, ox, oy, null);

            if (ox != 0 || oy != 0) {
                paintPanGap(g, ox, oy);
            }
            else {
                paintSelection(g);
                paintDragRectangle(g);
                paintHint(g);
            }
        }
        finally {
            g.dispose();
        }
    }

    /** Fills the strip a pan has dragged into view, so it reads as empty, not stale. */
    private void paintPanGap(Graphics2D g, int ox, int oy) {
        g.setColor(getBackground());
        if (ox > 0) g.fillRect(0, 0, ox, getHeight());
        if (ox < 0) g.fillRect(getWidth() + ox, 0, -ox, getHeight());
        if (oy > 0) g.fillRect(0, 0, getWidth(), oy);
        if (oy < 0) g.fillRect(0, getHeight() + oy, getWidth(), -oy);
    }

    /** The chosen rectangle, wherever it falls in the current view. */
    private void paintSelection(Graphics2D g) {
        if (selection == null || baseProjection == null) return;
        final Point2D.Double nw = baseProjection.toPixel(selection.north(), selection.west());
        final Point2D.Double se = baseProjection.toPixel(selection.south(), selection.east());

        final int x = (int) Math.round(Math.min(nw.x, se.x));
        final int y = (int) Math.round(Math.min(nw.y, se.y));
        final int w = (int) Math.round(Math.abs(se.x - nw.x));
        final int h = (int) Math.round(Math.abs(se.y - nw.y));
        if (w <= 0 || h <= 0) return;

        g.setColor(SELECTION_FILL);
        g.fillRect(x, y, w, h);
        g.setColor(SELECTION_EDGE);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(x, y, w, h);

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        final String label = String.format("%.2f° × %.2f°",
                                           selection.widthDegrees(), selection.heightDegrees());
        g.drawString(label, x + 4, y + 14);
    }

    /** The rubber band while a selection drag is in progress. */
    private void paintDragRectangle(Graphics2D g) {
        if (dragStart == null || dragNow == null || !draggingSelection) return;
        final int x = Math.min(dragStart.x, dragNow.x);
        final int y = Math.min(dragStart.y, dragNow.y);
        final int w = Math.abs(dragStart.x - dragNow.x);
        final int h = Math.abs(dragStart.y - dragNow.y);

        g.setColor(SELECTION_FILL);
        g.fillRect(x, y, w, h);
        g.setColor(SELECTION_EDGE);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                    10f, new float[]{5f, 4f}, 0f));
        g.drawRect(x, y, w, h);
    }

    private void paintHint(Graphics2D g) {
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        final String hint = mode == Mode.SELECT
                ? "Drag to select an area · right-drag to pan · wheel to zoom"
                : "Drag to pan · shift-drag to select an area · wheel to zoom";
        final int textWidth = g.getFontMetrics().stringWidth(hint);
        // Clear of the graticule's longitude labels, which run along the very
        // bottom edge.
        final Rectangle box = new Rectangle(8, getHeight() - 48, textWidth + 12, 18);

        g.setColor(new Color(255, 255, 255, 205));
        g.fillRoundRect(box.x, box.y, box.width, box.height, 6, 6);
        g.setColor(HINT);
        g.drawString(hint, box.x + 6, box.y + 13);
    }

    /** Letterboxes a composited result, which has its own size and aspect ratio. */
    private void paintResult(Graphics2D g) {
        final double scale = Math.min((double) getWidth() / resultImage.getWidth(),
                                      (double) getHeight() / resultImage.getHeight());
        final int w = (int) Math.round(resultImage.getWidth() * scale);
        final int h = (int) Math.round(resultImage.getHeight() * scale);
        g.drawImage(resultImage, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
    }

    /** The projection of the displayed result, for callers that need it. */
    public MapProjection resultProjection() { return resultProjection; }
}
