package org.weathermap.gui;

import org.weathermap.model.BoundingBox;
import org.weathermap.model.MapProjection;
import org.weathermap.model.RenderSpec;
import org.weathermap.osm.Feature;
import org.weathermap.render.Compositor;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import javax.swing.UIManager;
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

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(MapPanel.class.getName());

    /** What a plain drag does. */
    public enum Mode {
        /** Drag moves the map. Shift-drag still selects. */
        PAN,
        /** Drag draws the download rectangle. Right-drag still pans. */
        SELECT
    }

    private static final Color SELECTION_FILL = new Color(70, 130, 200, 55);
    private static final Color SELECTION_EDGE = new Color(20, 80, 170);
    /** Fallbacks, used only when no look and feel has been installed. */
    private static final Color DEFAULT_SURROUND = new Color(236, 240, 244);
    private static final Color DEFAULT_HINT = new Color(90, 90, 90);

    /** One wheel notch. Chosen to feel like a web map rather than a microscope. */
    private static final double ZOOM_STEP = 1.25;

    private final RenderSpec spec;
    private final MapView view;

    private List<Feature> features = List.of();
    private Mode mode = Mode.PAN;

    /** The last finished base-map render, and the view it was drawn for. */
    private BufferedImage baseImage;
    private MapProjection baseProjection;

    /**
     * Which state the map is in, which state is on screen, and which is being
     * drawn - all three as counter values.
     *
     * <p>{@link #renderGeneration} goes up whenever the view, the size or the
     * features change. {@link #shownGeneration} is the one {@link #baseImage}
     * was drawn for, and {@link #renderingGeneration} the one the worker is
     * busy with. The frame is current when the first two agree, and everything
     * else follows from comparing them.</p>
     */
    private long renderGeneration;
    private long shownGeneration = -1;
    private long renderingGeneration = -1;

    /**
     * Where the base map is drawn, which is not the event thread.
     *
     * <p>Thirteen degrees of loaded OSM coastline takes over a hundred
     * milliseconds to composite, and it used to take it inside
     * {@code paintComponent} - so every pan, zoom and resize froze the window
     * for that long, and a big enough area froze it for half a second at a
     * time. The last finished frame is shown, stretched to where its ground
     * now falls, until the new one arrives.</p>
     *
     * <p>One thread, so two renders never run at once and the newest request
     * simply waits for the one in flight; a superseded result is dropped when
     * it lands rather than cancelled mid-draw.</p>
     */
    private final java.util.concurrent.ExecutorService renderer =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "map-render");
                t.setDaemon(true);
                return t;
            });

    /** A composited weather map, shown until the view moves. */
    private BufferedImage resultImage;
    private MapProjection resultProjection;
    private boolean showingResult;

    /** The chosen download rectangle, drawn as an overlay. */
    private BoundingBox selection;

    private Consumer<BoundingBox> selectionListener = bbox -> { };
    private Consumer<BoundingBox> viewListener = bbox -> { };

    /** Told when Escape abandons a pick, so the status line can stop asking. */
    private Runnable pickCancelled = () -> { };

    private Point dragStart;
    private Point dragNow;
    private boolean draggingSelection;

    /**
     * Armed while the next click is to be taken as a point rather than a pan.
     *
     * <p>A one-shot, not a {@link Mode}. Picking a point is something that
     * happens once at the start of a task - the tide chart asks where before
     * it asks anything else - and a mode would have to be left as well as
     * entered, which is a state to get stuck in for no gain. Panning and
     * zooming carry on meanwhile, because finding the spot is most of the
     * job.</p>
     */
    private Consumer<double[]> pointPicker;

    /**
     * Re-reads the theme's colours when the look and feel changes.
     *
     * <p>{@code SwingUtilities.updateComponentTreeUI} rebuilds a component's UI
     * delegate but leaves any background set explicitly on it, so without this
     * the letterboxing round the map stays the colour of whichever theme was
     * installed at startup - a pale frame in a dark window.</p>
     *
     * <p><b>The map itself does not follow the theme, deliberately.</b> It is
     * drawn by the same {@link Compositor} that writes the PNG, and that image
     * is a chart: it gets saved, printed and read beside paper ones, so its
     * colours answer to the conventions of a weather chart rather than to the
     * time of day. Theming it here would either fork the renderer - the one
     * thing this design refuses, since the point is that what is seen and what
     * is saved cannot differ - or change the output of the command-line tool to
     * match a setting in a window it never opens. So the map stays a light
     * document in a dark room, the way a page does in a reader.</p>
     */
    @Override
    public void updateUI() {
        super.updateUI();
        setBackground(surround());
    }

    private static Color surround() {
        final Color c = UIManager.getColor("Panel.background");
        return c != null ? c : DEFAULT_SURROUND;
    }

    private static Color hintForeground() {
        final Color c = UIManager.getColor("Label.foreground");
        return c != null ? c : DEFAULT_HINT;
    }

    public MapPanel(RenderSpec spec, BoundingBox initialView) {
        this.spec = spec;
        this.view = new MapView(initialView);
        this.selection = initialView;

        setBackground(surround());
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
                if (pointPicker != null) {
                    if (SwingUtilities.isLeftMouseButton(e)) takePoint(e.getPoint());
                    return;                  // no zoom-on-double-click while picking
                }
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

        // Escape belongs to the pick and to nothing else here, so the action
        // is registered once and does nothing when no pick is armed rather
        // than being bound and unbound around it.
        getInputMap(WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "cancelPick");
        getActionMap().put("cancelPick", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (pointPicker == null) return;
                cancelPick();
                pickCancelled.run();
            }
        });

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
        // While a point is being picked, every drag pans. Dragging out a
        // download rectangle when the question on screen is "where?" would be
        // answering a different one.
        if (pointPicker != null) return false;
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

    /** Told when Escape abandons an armed pick. */
    public void setPickCancelledListener(Runnable listener) {
        this.pickCancelled = listener == null ? () -> { } : listener;
    }

    /**
     * Takes the next left click as a geographic point.
     *
     * <p>Panning, zooming and the wheel all keep working while this is armed -
     * scrolling to the right place is most of what choosing a point consists
     * of. Escape cancels, and so does a second call to
     * {@link #cancelPick()}.</p>
     *
     * @param whenPicked given {@code {lat, lon}} on the EDT, once
     */
    public void pickPoint(Consumer<double[]> whenPicked) {
        this.pointPicker = whenPicked;
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        requestFocusInWindow();
        repaint();
    }

    /** Disarms a pick, leaving the map as it was. */
    public void cancelPick() {
        if (pointPicker == null) return;
        pointPicker = null;
        setCursor(Cursor.getPredefinedCursor(
                mode == Mode.SELECT ? Cursor.CROSSHAIR_CURSOR : Cursor.MOVE_CURSOR));
        repaint();
    }

    /** True while the next click will be taken as a point. */
    public boolean isPicking() { return pointPicker != null; }

    /** Hands the picker the position under {@code p} and disarms. */
    private void takePoint(Point p) {
        final double[] at = latLonAt(p);
        if (at == null) return;
        final Consumer<double[]> picker = pointPicker;
        // Disarmed before the callback, not after: the callback puts a modal
        // dialog on screen, and a pick still armed underneath it takes the
        // click that dismisses it as a second point.
        cancelPick();
        picker.accept(at);
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

    /**
     * Marks the frame on screen as out of date without throwing it away.
     *
     * <p>Keeping it is the whole point: it is what there is to show while the
     * next one is drawn. Clearing it here is what made a pan blank the map.</p>
     */
    private void invalidateRender() {
        renderGeneration++;
    }

    private void finishPan(Point from, Point to) {
        final int dx = to.x - from.x;
        final int dy = to.y - from.y;
        if (dx == 0 && dy == 0) return;

        final BoundingBox bounds = view.bounds(Math.max(1, getWidth()), Math.max(1, getHeight()));
        final double perPixelLon = bounds.widthDegrees() / Math.max(1, getWidth());
        final double perPixelLat = bounds.heightDegrees() / Math.max(1, getHeight());
        // Dragging right moves the map right, so the view moves west.
        view.panBy(dy * perPixelLat, -dx * perPixelLon);
        viewMoved();
    }

    /**
     * Turns a drag into the chosen rectangle.
     *
     * <p>West and east come from which corner was <em>further left on screen</em>,
     * not from which longitude is the smaller number. On a view that crosses the
     * antimeridian those two answers differ: a drag from 170&deg;E to 170&deg;W
     * is twenty degrees of Pacific read left to right, and taking the smaller
     * number as west would instead select the three hundred and forty degrees
     * the other way round - every part of the world except the part under the
     * pointer.</p>
     */
    private void finishSelection(Point from, Point to) {
        if (Math.abs(from.x - to.x) < 5 || Math.abs(from.y - to.y) < 5) return;

        final double[] left = latLonAt(from.x <= to.x ? from : to);
        final double[] right = latLonAt(from.x <= to.x ? to : from);
        if (left == null || right == null) return;

        try {
            final BoundingBox bbox = selectionBetween(left, right);
            selection = bbox;
            showingResult = false;
            selectionListener.accept(bbox);
        }
        catch (IllegalArgumentException e) {
            // Too small, or dragged off the edge. Ignore rather than complain -
            // a stray drag is not an error worth a dialog.
        }
    }

    /**
     * The box between the left-hand and right-hand corners of a drag.
     *
     * <p>Package-private and static so the rule can be asserted without a
     * window: it is the whole of what makes a Pacific selection possible, and
     * it is one line that looks like an ordinary min/max until it is not.</p>
     */
    static BoundingBox selectionBetween(double[] left, double[] right) {
        return BoundingBox.of(
                Math.min(left[0], right[0]), BoundingBox.normaliseLon(left[1]),
                Math.max(left[0], right[0]), BoundingBox.normaliseEastLon(right[1]));
    }

    /** @return {@code {lat, lon}} under a panel point, or null before first render */
    private double[] latLonAt(Point p) {
        final MapProjection projection = viewProjection();
        if (projection == null) return null;
        return projection.toLatLon(p.x, p.y);
    }

    // ---- rendering ------------------------------------------------------

    /**
     * The projection for where the map is looking <em>now</em>, drawn or not.
     *
     * <p>Which is the one every mouse calculation wants. It used to be the
     * projection of the last render, and getting one meant doing a render -
     * so a click had to wait for a repaint before it knew what it had hit.
     * Now that a frame on screen may be one view behind, the two are
     * different things and asking for the wrong one would put a click in the
     * wrong place for as long as a render takes.</p>
     */
    private MapProjection viewProjection() {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return null;
        return baseSpec(w, h).projectionFor(view.bounds(w, h));
    }

    /**
     * The render settings for the selection map: everything the chart has,
     * without the weather.
     *
     * <p>At panel size rather than {@link RenderSpec}'s output size, so that
     * panel pixels and image pixels are the same thing - which is what makes
     * the hit testing for pan, zoom and selection exact instead of
     * approximately right.</p>
     */
    private RenderSpec baseSpec(int w, int h) {
        final RenderSpec base = spec.copy();
        base.setMaxSize(w, h);
        base.setEnabled(RenderSpec.LayerKind.GRIB, false);
        base.setEnabled(RenderSpec.LayerKind.GRATICULE, true);
        base.setEnabled(RenderSpec.LayerKind.ANNOTATION, false);   // no legend to show
        return base;
    }

    /**
     * True when what is on screen was drawn for the view as it is now.
     *
     * <p>False between a pan and the frame that answers it, while the last
     * one is stretched into place.</p>
     */
    public boolean hasCurrentFrame() {
        return baseImage != null && shownGeneration == renderGeneration;
    }

    /** True when the frame on screen is neither current nor already being replaced. */
    private boolean needsRender() {
        return getWidth() > 0 && getHeight() > 0
                && shownGeneration != renderGeneration
                && renderingGeneration != renderGeneration;
    }

    /**
     * Starts drawing the current state on the render thread.
     *
     * <p>Everything the worker needs is taken here, on the event thread, and
     * nothing shared is touched over there: the spec is a copy, the feature
     * list is replaced wholesale rather than mutated, and the result is handed
     * back through {@code invokeLater}.</p>
     */
    private void startRender() {
        final int w = getWidth();
        final int h = getHeight();
        final long mine = renderGeneration;
        renderingGeneration = mine;

        final BoundingBox bounds = view.bounds(w, h);
        final RenderSpec base = baseSpec(w, h);
        final List<Feature> drawing = features;

        renderer.submit(() -> {
            final MapProjection projection = base.projectionFor(bounds);
            final BufferedImage image;
            try {
                image = new Compositor(base).render(bounds, drawing, List.of(), "");
            }
            catch (RuntimeException e) {
                // A render that throws must not take the thread with it, or
                // the map silently stops updating for the rest of the session.
                LOG.log(java.util.logging.Level.WARNING, "Could not draw the base map", e);
                SwingUtilities.invokeLater(() -> renderingGeneration = -1);
                return;
            }
            SwingUtilities.invokeLater(() -> {
                renderingGeneration = -1;
                if (mine <= shownGeneration) return;    // a newer frame won the race
                baseImage = image;
                baseProjection = projection;
                shownGeneration = mine;
                repaint();
            });
        });
    }

    /** Stops the render thread. Call when the window closes. */
    public void dispose() {
        renderer.shutdownNow();
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

            if (needsRender()) startRender();
            if (baseImage == null) {
                paintNothingYet(g);
                return;
            }

            // A pan in progress is shown by offsetting the last render; the
            // real one happens on release.
            int ox = 0;
            int oy = 0;
            if (dragStart != null && dragNow != null && !draggingSelection) {
                ox = dragNow.x - dragStart.x;
                oy = dragNow.y - dragStart.y;
            }

            // Whatever is drawn below, the pixels end up showing the view as
            // it is now: a current frame one-to-one, a stale one stretched to
            // where its ground falls. So every overlay is placed against the
            // current view, and none of them against the frame's own
            // projection - which is a view behind for as long as a render
            // takes, and put the selection rectangle in the wrong place for
            // exactly that long.
            final MapProjection now = viewProjection();
            if (shownGeneration == renderGeneration || now == null) {
                g.drawImage(baseImage, ox, oy, null);
            }
            else {
                paintLastFrameWhereItNowFalls(g, now, ox, oy);
            }

            if (ox != 0 || oy != 0) {
                paintPanGap(g, ox, oy);
            }
            else {
                paintSelection(g, now);
                paintDragRectangle(g);
                paintHint(g);
            }
        }
        finally {
            g.dispose();
        }
    }

    /**
     * Draws the last finished frame stretched to where its ground falls in the
     * view as it is now.
     *
     * <p>This is what makes an off-thread render feel like a map rather than a
     * stutter. The alternative - drawing the old frame square at the origin
     * until the new one lands - shows the map jumping back to where it was and
     * then forward again, which is worse than the freeze it replaced.</p>
     *
     * <p>A pure pan makes this a translation and a zoom makes it a scale, both
     * exact; only a change of projection or of aspect makes it an
     * approximation, and then only for the tenth of a second before the real
     * frame arrives.</p>
     */
    private void paintLastFrameWhereItNowFalls(Graphics2D g, MapProjection now, int ox, int oy) {
        final BoundingBox was = baseProjection.bounds();
        final Point2D.Double nw = now.toPixel(was.north(), was.west());
        final Point2D.Double se = now.toPixel(was.south(), was.east());

        // The east edge is east of the west edge by definition, so an x that
        // has come out to the left of it has wrapped - the same correction
        // the selection overlay needs, and for the same reason.
        final double turn = 360.0 / now.bounds().widthDegrees() * now.imageWidth();
        double right = se.x;
        while (right <= nw.x) right += turn;

        final int x1 = (int) Math.round(nw.x) + ox;
        final int y1 = (int) Math.round(nw.y) + oy;
        final int x2 = (int) Math.round(right) + ox;
        final int y2 = (int) Math.round(se.y) + oy;
        if (x2 <= x1 || y2 <= y1) return;

        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.drawImage(baseImage, x1, y1, x2, y2, 0, 0,
                    baseImage.getWidth(), baseImage.getHeight(), null);
    }

    /** Before the very first frame there is nothing to stretch, so say so. */
    private void paintNothingYet(Graphics2D g) {
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        g.setColor(hintForeground());
        final String message = "Drawing the map…";
        g.drawString(message,
                     (getWidth() - g.getFontMetrics().stringWidth(message)) / 2,
                     getHeight() / 2);
    }

    /** Fills the strip a pan has dragged into view, so it reads as empty, not stale. */
    private void paintPanGap(Graphics2D g, int ox, int oy) {
        g.setColor(getBackground());
        if (ox > 0) g.fillRect(0, 0, ox, getHeight());
        if (ox < 0) g.fillRect(getWidth() + ox, 0, -ox, getHeight());
        if (oy > 0) g.fillRect(0, 0, getWidth(), oy);
        if (oy < 0) g.fillRect(0, getHeight() + oy, getWidth(), -oy);
    }

    /**
     * The chosen rectangle, wherever it falls in the current view.
     *
     * <p>Drawn twice, one turn of longitude apart. The view and the selection
     * wrap independently, so a selection that lies off the right edge of the
     * view may be the same selection that lies off its left edge - and a
     * selection straddling the edge has to appear at both. One of the two
     * copies is almost always off the panel entirely, and clipping is free.</p>
     */
    private void paintSelection(Graphics2D g, MapProjection now) {
        if (selection == null || now == null) return;
        final Point2D.Double nw = now.toPixel(selection.north(), selection.west());
        final Point2D.Double se = now.toPixel(selection.south(), selection.east());

        // The east edge is east of the west edge by definition, so a pixel x
        // that has come out to the left of it has wrapped and belongs a whole
        // turn further on.
        final double turn = 360.0 / now.bounds().widthDegrees() * now.imageWidth();
        // Not <, but <=: a selection of the whole world puts both edges on the
        // same pixel, and it is a full turn wide rather than nothing wide.
        double right = se.x;
        while (right <= nw.x) right += turn;

        final int y = (int) Math.round(Math.min(nw.y, se.y));
        final int w = (int) Math.round(right - nw.x);
        final int h = (int) Math.round(Math.abs(se.y - nw.y));
        if (w <= 0 || h <= 0) return;

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        final String label = String.format("%.2f° × %.2f°",
                                           selection.widthDegrees(), selection.heightDegrees());

        for (int copy = 0; copy < 2; copy++) {
            final int x = (int) Math.round(nw.x - copy * turn);
            if (x + w < 0 || x > getWidth()) continue;

            g.setColor(SELECTION_FILL);
            g.fillRect(x, y, w, h);
            g.setColor(SELECTION_EDGE);
            g.setStroke(new BasicStroke(2f));
            g.drawRect(x, y, w, h);
            g.drawString(label, x + 4, y + 14);
        }
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
        final String hint;
        if (pointPicker != null) {
            hint = "Click the point to read the tide at · drag to pan · wheel to zoom "
                    + "· Esc to cancel";
        }
        else {
            hint = mode == Mode.SELECT
                    ? "Drag to select an area · right-drag to pan · wheel to zoom"
                    : "Drag to pan · shift-drag to select an area · wheel to zoom";
        }
        final int textWidth = g.getFontMetrics().stringWidth(hint);
        // Clear of the graticule's longitude labels, which run along the very
        // bottom edge.
        final Rectangle box = new Rectangle(8, getHeight() - 48, textWidth + 12, 18);

        // The pill is chrome sitting on the map, so it follows the theme even
        // though the map under it does not.
        final Color plate = surround();
        g.setColor(new Color(plate.getRed(), plate.getGreen(), plate.getBlue(), 225));
        g.fillRoundRect(box.x, box.y, box.width, box.height, 6, 6);
        g.setColor(hintForeground());
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
