package org.weathermap.gui;

import org.weathermap.model.BoundingBox;
import org.weathermap.model.MapProjection;
import org.weathermap.model.RenderSpec;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Shows the composited map and lets the user drag out the area to download.
 *
 * <p>The drag rectangle is kept in <em>pixels</em> while it is being drawn and
 * converted to a {@link BoundingBox} only on release, through the projection of
 * the image currently displayed. That is what makes the selection mean the same
 * thing as what the user sees, at any zoom, under either projection.</p>
 *
 * <p>When there is no image yet the panel still accepts a drag, against the
 * projection of the current default area - so the first selection does not
 * require a map to exist first.</p>
 */
public final class MapPanel extends JPanel {

    private static final Color SELECTION_FILL = new Color(70, 130, 200, 60);
    private static final Color SELECTION_EDGE = new Color(30, 90, 170);

    private BufferedImage image;
    private MapProjection projection;
    private Consumer<BoundingBox> selectionListener;

    private Point dragStart;
    private Point dragEnd;

    public MapPanel() {
        setBackground(new Color(245, 245, 245));
        setPreferredSize(new Dimension(900, 620));

        final MouseAdapter drag = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint();
                dragEnd = e.getPoint();
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                dragEnd = e.getPoint();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragEnd = e.getPoint();
                finishSelection();
                dragStart = null;
                dragEnd = null;
                repaint();
            }
        };
        addMouseListener(drag);
        addMouseMotionListener(drag);
    }

    /** Called with the new area each time the user finishes a drag. */
    public void setSelectionListener(Consumer<BoundingBox> listener) {
        this.selectionListener = listener;
    }

    /** Shows a freshly composited map. */
    public void setImage(BufferedImage image, MapProjection projection) {
        this.image = image;
        this.projection = projection;
        repaint();
    }

    /**
     * Sets the projection used to interpret drags before any map has been
     * rendered, so the very first selection works.
     */
    public void setProjectionOnly(RenderSpec spec, BoundingBox area) {
        if (image == null) this.projection = spec.projectionFor(area);
    }

    private void finishSelection() {
        if (projection == null || dragStart == null || dragEnd == null) return;
        if (Math.abs(dragStart.x - dragEnd.x) < 4 || Math.abs(dragStart.y - dragEnd.y) < 4) {
            return;                                     // a click, not a drag
        }
        final Rectangle r = imageRect();
        if (r == null) return;

        // Convert to image coordinates first: the image is letterboxed inside
        // the panel, so panel pixels are not image pixels.
        final double sx = (double) projection.imageWidth() / r.width;
        final double sy = (double) projection.imageHeight() / r.height;
        final double x1 = (Math.min(dragStart.x, dragEnd.x) - r.x) * sx;
        final double x2 = (Math.max(dragStart.x, dragEnd.x) - r.x) * sx;
        final double y1 = (Math.min(dragStart.y, dragEnd.y) - r.y) * sy;
        final double y2 = (Math.max(dragStart.y, dragEnd.y) - r.y) * sy;

        final double[] topLeft = projection.toLatLon(x1, y1);
        final double[] bottomRight = projection.toLatLon(x2, y2);

        try {
            final BoundingBox bbox = BoundingBox.of(
                    bottomRight[0], topLeft[1], topLeft[0], bottomRight[1]);
            if (selectionListener != null) selectionListener.accept(bbox);
        }
        catch (IllegalArgumentException e) {
            // Too small, or dragged off the edge: ignore rather than complain.
        }
    }

    /** Where the image is drawn inside the panel, preserving its aspect ratio. */
    private Rectangle imageRect() {
        if (projection == null) return null;
        final int iw = (image != null) ? image.getWidth() : projection.imageWidth();
        final int ih = (image != null) ? image.getHeight() : projection.imageHeight();
        final double scale = Math.min((double) getWidth() / iw, (double) getHeight() / ih);
        final int w = (int) Math.round(iw * scale);
        final int h = (int) Math.round(ih * scale);
        return new Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                               RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                               RenderingHints.VALUE_ANTIALIAS_ON);

            final Rectangle r = imageRect();
            if (image != null && r != null) {
                g.drawImage(image, r.x, r.y, r.width, r.height, null);
            }
            else {
                g.setColor(new Color(120, 120, 120));
                final String msg = "Select an area and choose Download to build a map";
                g.drawString(msg, (getWidth() - g.getFontMetrics().stringWidth(msg)) / 2,
                             getHeight() / 2);
            }

            if (dragStart != null && dragEnd != null) {
                final int x = Math.min(dragStart.x, dragEnd.x);
                final int y = Math.min(dragStart.y, dragEnd.y);
                final int w = Math.abs(dragStart.x - dragEnd.x);
                final int h = Math.abs(dragStart.y - dragEnd.y);
                g.setColor(SELECTION_FILL);
                g.fillRect(x, y, w, h);
                g.setColor(SELECTION_EDGE);
                g.setStroke(new BasicStroke(1.5f));
                g.drawRect(x, y, w, h);
            }
        }
        finally {
            g.dispose();
        }
    }
}
