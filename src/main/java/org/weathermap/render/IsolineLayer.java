package org.weathermap.render;

import org.weathermap.grib.Grid;
import org.weathermap.model.MapProjection;
import org.weathermap.model.RenderSpec;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Isolines - isobars for pressure, contours for geopotential height.
 *
 * <p>The line a synoptic chart is read by. Barbs say what the wind is doing at
 * a point; isobars say what the whole field is doing, and where it is going
 * next: their spacing is the pressure gradient, so tight lines mean strong wind
 * whether or not a barb happens to have been drawn there.</p>
 *
 * <h2>Marching squares</h2>
 *
 * <p>The field is sampled onto a lattice of output pixels, and each lattice cell
 * is classified by which of its four corners are above the contour level. That
 * gives sixteen cases, of which fourteen are one or two line segments and two
 * are ambiguous saddles, resolved by comparing the cell's centre against the
 * level. Crossing points are placed by linear interpolation along the edge, so
 * the lines are smooth rather than stepped.</p>
 *
 * <p>Sampling every {@value #SAMPLE_STEP} pixels rather than every pixel is what
 * makes this cheap, and costs nothing: the field underneath is a 0.25&deg; grid,
 * which over a chart this size is coarser than the lattice anyway. Contouring
 * at pixel resolution would trace interpolation artefacts, not weather.</p>
 *
 * <h2>Why the segments are not stitched into paths</h2>
 *
 * <p>Marching squares emits unordered segments. Joining them into polylines is
 * the usual next step and is not needed here: drawn, the segments are already
 * contiguous, and labels are placed on individual segments chosen for their
 * spacing. Stitching would buy smoothing and gap-in-the-line labelling, neither
 * of which is worth the code until someone asks for it.</p>
 */
public final class IsolineLayer implements Layer {

    /** Lattice spacing in output pixels. */
    private static final int SAMPLE_STEP = 4;

    /** Never draw more than this many levels, whatever the range says. */
    private static final int MAX_LEVELS = 60;

    /** Minimum gap between two labels of the same contour, in pixels. */
    private static final double LABEL_SPACING = 260;

    /**
     * How a variable's isolines are spaced and written.
     *
     * @param interval   contour spacing, in the variable's own units
     * @param emphasis   every n-th interval drawn heavier, or 0 for none
     * @param labelScale divide the value by this before writing it
     * @param label      what the written number is in
     */
    private record Style(double interval, int emphasis, double labelScale, String label) { }

    /**
     * The conventions, which are not arbitrary.
     *
     * <p>Four hectopascals is what marine and synoptic charts use, so the
     * spacing of the lines means the same thing here as on a chart from
     * anywhere else - which is the entire value of a convention. Sixty
     * geopotential metres is the equivalent at 500 mb.</p>
     */
    private static Style styleFor(Grid grid) {
        return switch (grid.variable().code()) {
            // GRIB carries pressure in pascals; charts are written in
            // hectopascals, so 4 hPa is an interval of 400.
            case "PRMSL", "MSLET", "PRES" -> new Style(400, 5, 100, "hPa");
            case "HGT" -> new Style(60, 5, 1, "gpm");
            default -> new Style(niceInterval(grid), 5, 1, "");
        };
    }

    /** For a variable with no convention: about ten lines across its range. */
    private static double niceInterval(Grid grid) {
        final float[] range = grid.range();
        final double span = range[1] - range[0];
        if (!(span > 0)) return 1;

        final double rough = span / 10;
        final double magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
        final double normalised = rough / magnitude;
        final double step = normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 5 ? 5 : 10;
        return step * magnitude;
    }

    private final Grid grid;
    private final Style style;

    /**
     * A warm dark brown, chosen against what is already on the map.
     *
     * <p>The coastline is dark blue and the boundaries a dusty purple, so a cool
     * colour would read as more of the base map; the barbs are near-black and
     * have to stay the most prominent thing, so isobars must not be black
     * either. Brown is what is left, and it is also what paper charts use.</p>
     */
    private final Color ink = new Color(138, 74, 44);

    private final Color halo = new Color(255, 255, 255, 205);

    public IsolineLayer(Grid grid) {
        this.grid = grid;
        this.style = styleFor(grid);
    }

    public Grid grid() { return grid; }

    /** The contour interval in the units labels are written in, for the caption. */
    public double labelledInterval() { return style.interval() / style.labelScale(); }

    public String labelUnit() { return style.label(); }

    @Override
    public RenderSpec.LayerKind kind() { return RenderSpec.LayerKind.ISOBARS; }

    @Override
    public void draw(Graphics2D g, MapProjection projection) {
        final float[] field = sample(projection);
        final int cols = latticeWidth(projection);
        final int rows = latticeHeight(projection);

        final float[] range = grid.range();
        if (Float.isNaN(range[0]) || Float.isNaN(range[1])) return;

        g.setFont(g.getFont().deriveFont(Font.BOLD, 10.5f));

        final double first = Math.ceil(range[0] / style.interval()) * style.interval();
        int drawn = 0;
        for (double level = first; level <= range[1] && drawn < MAX_LEVELS;
             level += style.interval(), drawn++) {

            final List<Line2D.Double> segments = trace(field, cols, rows, level);
            if (segments.isEmpty()) continue;

            final boolean heavy = style.emphasis() > 0
                    && Math.round(level / style.interval()) % style.emphasis() == 0;
            g.setStroke(new BasicStroke(heavy ? 2.0f : 1.1f,
                                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(ink);
            for (Line2D.Double s : segments) g.draw(s);

            label(g, segments, level);
        }
    }

    // ---- sampling --------------------------------------------------------

    private static int latticeWidth(MapProjection p) {
        return p.imageWidth() / SAMPLE_STEP + 2;
    }

    private static int latticeHeight(MapProjection p) {
        return p.imageHeight() / SAMPLE_STEP + 2;
    }

    private float[] sample(MapProjection projection) {
        final int cols = latticeWidth(projection);
        final int rows = latticeHeight(projection);
        final float[] out = new float[cols * rows];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                final double[] latLon =
                        projection.toLatLon(col * SAMPLE_STEP, row * SAMPLE_STEP);
                out[row * cols + col] = grid.sample(latLon[0], latLon[1]);
            }
        }
        return out;
    }

    // ---- marching squares ------------------------------------------------

    private List<Line2D.Double> trace(float[] field, int cols, int rows, double level) {
        final List<Line2D.Double> out = new ArrayList<>();
        for (int row = 0; row < rows - 1; row++) {
            for (int col = 0; col < cols - 1; col++) {
                final float topLeft = field[row * cols + col];
                final float topRight = field[row * cols + col + 1];
                final float bottomRight = field[(row + 1) * cols + col + 1];
                final float bottomLeft = field[(row + 1) * cols + col];

                // One missing corner makes the cell undecidable; the field is
                // cut to the requested box, so this happens along its edges.
                if (Float.isNaN(topLeft) || Float.isNaN(topRight)
                        || Float.isNaN(bottomRight) || Float.isNaN(bottomLeft)) {
                    continue;
                }
                cell(out, col, row, topLeft, topRight, bottomRight, bottomLeft, level);
            }
        }
        return out;
    }

    private void cell(List<Line2D.Double> out, int col, int row,
                      float topLeft, float topRight, float bottomRight, float bottomLeft,
                      double level) {

        int code = 0;
        if (topLeft >= level) code |= 8;
        if (topRight >= level) code |= 4;
        if (bottomRight >= level) code |= 2;
        if (bottomLeft >= level) code |= 1;
        if (code == 0 || code == 15) return;

        final double x = col * SAMPLE_STEP;
        final double y = row * SAMPLE_STEP;

        final Point2D.Double top = new Point2D.Double(x + cross(topLeft, topRight, level), y);
        final Point2D.Double bottom =
                new Point2D.Double(x + cross(bottomLeft, bottomRight, level), y + SAMPLE_STEP);
        final Point2D.Double left = new Point2D.Double(x, y + cross(topLeft, bottomLeft, level));
        final Point2D.Double right =
                new Point2D.Double(x + SAMPLE_STEP, y + cross(topRight, bottomRight, level));

        switch (code) {
            case 1, 14 -> add(out, left, bottom);
            case 2, 13 -> add(out, bottom, right);
            case 3, 12 -> add(out, left, right);
            case 4, 11 -> add(out, top, right);
            case 6, 9  -> add(out, top, bottom);
            case 7, 8  -> add(out, left, top);
            // The two saddles. Which pair of corners the lines separate cannot
            // be told from the corners alone, so the cell's own centre decides -
            // getting this wrong joins two systems that are not connected.
            case 5 -> {
                final double centre = (topLeft + topRight + bottomRight + bottomLeft) / 4.0;
                if (centre >= level) {
                    add(out, left, top);
                    add(out, bottom, right);
                }
                else {
                    add(out, left, bottom);
                    add(out, top, right);
                }
            }
            case 10 -> {
                final double centre = (topLeft + topRight + bottomRight + bottomLeft) / 4.0;
                if (centre >= level) {
                    add(out, left, bottom);
                    add(out, top, right);
                }
                else {
                    add(out, left, top);
                    add(out, bottom, right);
                }
            }
            default -> { }
        }
    }

    private static void add(List<Line2D.Double> out, Point2D.Double a, Point2D.Double b) {
        out.add(new Line2D.Double(a, b));
    }

    /** Where along an edge between two corner values the level is crossed. */
    private static double cross(double from, double to, double level) {
        final double delta = to - from;
        if (Math.abs(delta) < 1e-9) return SAMPLE_STEP / 2.0;
        final double t = (level - from) / delta;
        return Math.max(0, Math.min(1, t)) * SAMPLE_STEP;
    }

    // ---- labels ----------------------------------------------------------

    /**
     * Writes the level along its own contour.
     *
     * <p>An unlabelled isobar says only "somewhere between the two next to it",
     * which is most of the information gone. Labels are rotated to lie along the
     * line, kept upright, and spaced out so a short closed contour gets one and
     * a long one gets several.</p>
     */
    private void label(Graphics2D g, List<Line2D.Double> segments, double level) {
        final String text = format(level);
        final FontMetrics fm = g.getFontMetrics();
        final double width = fm.stringWidth(text);

        final List<Point2D.Double> placed = new ArrayList<>();
        for (Line2D.Double segment : segments) {
            final double mx = (segment.x1 + segment.x2) / 2;
            final double my = (segment.y1 + segment.y2) / 2;

            boolean tooClose = false;
            for (Point2D.Double other : placed) {
                if (other.distance(mx, my) < LABEL_SPACING) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;
            placed.add(new Point2D.Double(mx, my));

            double angle = Math.atan2(segment.y2 - segment.y1, segment.x2 - segment.x1);
            // Never upside down: a number is not symmetrical and a chart read at
            // a glance should not need the reader's head turning.
            if (angle > Math.PI / 2) angle -= Math.PI;
            if (angle < -Math.PI / 2) angle += Math.PI;

            final AffineTransform saved = g.getTransform();
            g.translate(mx, my);
            g.rotate(angle);

            final float tx = (float) (-width / 2);
            final float ty = fm.getAscent() / 2f - 1;
            g.setColor(halo);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) g.drawString(text, tx + dx, ty + dy);
                }
            }
            g.setColor(ink);
            g.drawString(text, tx, ty);
            g.setTransform(saved);
        }
    }

    private String format(double level) {
        final double shown = level / style.labelScale();
        return Math.abs(shown - Math.rint(shown)) < 0.05
                ? String.valueOf(Math.round(shown))
                : String.format("%.1f", shown);
    }
}
