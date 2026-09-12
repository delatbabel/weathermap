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

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(IsolineLayer.class.getName());

    /** Lattice spacing in output pixels. */
    private static final int SAMPLE_STEP = 4;

    /** Never draw more than this many levels, whatever the range says. */
    private static final int MAX_LEVELS = 60;

    /** Minimum gap between two labels of the same contour, in pixels. */
    private static final double LABEL_SPACING = 260;

    /**
     * How far a point must beat its surroundings to be a pressure centre, in
     * output pixels.
     *
     * <p>This is the whole of the definition. A high is not a point that happens
     * to be higher than the four next to it - at a quarter-degree resolution
     * half the map is - but one that is higher than everything for some distance
     * around, and the distance is what makes the difference between marking
     * weather systems and marking noise.</p>
     */
    private static final int CENTRE_RADIUS_PX = 110;

    /**
     * How far a centre must stand clear of its surroundings, as a fraction of
     * the contour interval.
     *
     * <p>Half an interval - two hectopascals for pressure. The test is really
     * for a <em>closed</em> contour: a system worth an H or an L has isobars
     * that go round it, and a bump on a slope does not. Half an interval is the
     * point at which one becomes likely.</p>
     *
     * <p>A quarter was tried first and marked two highs on one European ridge
     * and a one-hectopascal dip over the Gulf of Lion, neither of which a
     * forecaster would have drawn.</p>
     */
    private static final double MIN_PROMINENCE_FRACTION = 0.5;

    /**
     * How far apart two centres of the same kind must be before they are even
     * considered separate, as a multiple of the search radius.
     *
     * <p>A cheap first pass only. Whether two centres further apart than this
     * are one system or two is decided by the ground between them, not by the
     * distance across it.</p>
     */
    private static final double SUPPRESSION_FACTOR = 1.8;

    /** Lattice cells of smoothing before extrema are looked for. */
    private static final int SMOOTHING_RADIUS = 2;

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

    /**
     * Blue for a high, red for a low - the convention most charts use.
     *
     * <p>Both are pushed well clear of what is already on the map: the blue is
     * more saturated than the coastline's muted navy, and the red is nowhere
     * near the dusty purple of the boundaries or the brown of the isobars
     * themselves. A letter that could be mistaken for a boundary is worse than
     * no colour at all, and at this size and weight there is no ambiguity.</p>
     *
     * <p>The isobars stay brown. Colouring the lines by the system they belong
     * to is not a thing charts do, and could not be done anyway - one isobar
     * usually runs past several.</p>
     */
    private final Color highInk = new Color(20, 70, 175);

    private final Color lowInk = new Color(190, 32, 32);

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

        if (marksCentres()) drawCentres(g, field, cols, rows);
    }

    /**
     * Only pressure gets H and L.
     *
     * <p>The letters mean a high and a low, which is a statement about pressure
     * and not about whatever else might be contoured. The extrema of a
     * geopotential height field are ridges and troughs, and marking those with
     * an H would be worse than leaving them unmarked.</p>
     */
    private boolean marksCentres() {
        return switch (grid.variable().code()) {
            case "PRMSL", "MSLET", "PRES" -> true;
            default -> false;
        };
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

    // ---- pressure centres ------------------------------------------------

    /**
     * A high or a low.
     *
     * @param x     output pixels
     * @param y     output pixels
     * @param value the field's value there, in its own units
     * @param high  true for a maximum
     */
    record Centre(double x, double y, double value, boolean high) { }

    /**
     * Marks the systems.
     *
     * <p>These are the first thing a reader's eye goes to on a synoptic chart,
     * ahead of any individual isobar: the lines say what the gradient is doing,
     * the letters say what is driving it. A closed contour already implies a
     * centre, but finding it by eye means tracing rings inwards, and near the
     * edge of the chart the innermost ring may not be drawn at all.</p>
     */
    private void drawCentres(Graphics2D g, float[] field, int cols, int rows) {
        final List<Centre> centres = centresOf(
                field, cols, rows, SAMPLE_STEP,
                style.interval() * MIN_PROMINENCE_FRACTION);

        // An unmarked chart is ambiguous: it looks the same whether the
        // machinery failed or the weather simply has no centre in view. Over a
        // small area that is the usual case - the isobars run straight across
        // and the high driving them is a thousand kilometres away - and saying
        // so costs a log line and saves someone concluding the feature is
        // broken.
        if (centres.isEmpty()) {
            LOG.info("No pressure centre lies inside this area, so no H or L is "
                    + "marked. Centres outside the chart are not marked, because "
                    + "putting one on the edge would claim to know where it is. "
                    + "A wider area will find them.");
            return;
        }

        for (Centre centre : centres) {
            final Color colour = centre.high() ? highInk : lowInk;
            final String letter = centre.high() ? "H" : "L";
            // Whole hectopascals. A chart writes "1026", not "1026.3" - the
            // extra digit is below the accuracy of the analysis and reads as a
            // precision the forecast does not have.
            final String value = String.valueOf(Math.round(centre.value() / style.labelScale()));

            g.setFont(g.getFont().deriveFont(Font.BOLD, 26f));
            final FontMetrics letterMetrics = g.getFontMetrics();
            final float letterX = (float) (centre.x() - letterMetrics.stringWidth(letter) / 2.0);
            final float letterY = (float) (centre.y() + letterMetrics.getAscent() / 2.0 - 3);
            haloed(g, letter, letterX, letterY, colour);

            // The central pressure underneath, which is what turns "a low" into
            // "how deep a low".
            g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
            final FontMetrics valueMetrics = g.getFontMetrics();
            final float valueX = (float) (centre.x() - valueMetrics.stringWidth(value) / 2.0);
            haloed(g, value, valueX, letterY + valueMetrics.getHeight() - 1, colour);
        }
    }

    private void haloed(Graphics2D g, String text, float x, float y, Color colour) {
        g.setColor(halo);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (dx != 0 || dy != 0) g.drawString(text, x + dx, y + dy);
            }
        }
        g.setColor(colour);
        g.drawString(text, x, y);
    }

    /**
     * Finds the local extrema worth marking.
     *
     * <p>Three filters, each removing a different kind of false centre.</p>
     *
     * <p><b>Smoothing</b> first, because a quarter-degree field interpolated up
     * to chart size carries ripples that are extrema in the strict sense and
     * weather in no sense at all.</p>
     *
     * <p><b>A radius</b>, so that beating the immediately neighbouring samples
     * is not enough - a centre has to beat everything within
     * {@value #CENTRE_RADIUS_PX} pixels. This is also what stops one system
     * being marked several times: the same test, applied to a point near a real
     * centre, fails against that centre.</p>
     *
     * <p><b>Prominence</b>, so that a point at the top of a plateau is not
     * called a high. The centre has to stand clear of the ring around it by a
     * real amount, not merely be first past the post.</p>
     *
     * <p>Candidates within the radius of the edge are dropped outright. A field
     * that is still rising as it leaves the chart has its maximum on the border,
     * and that is not a high - it is a high somewhere off the map, and marking
     * the edge of the paper claims to know where it is.</p>
     */
    static List<Centre> centresOf(float[] raw, int cols, int rows, int step,
                                  double minProminence) {
        final float[] field = smooth(raw, cols, rows);
        final int radius = Math.max(2, CENTRE_RADIUS_PX / step);

        final List<Centre> found = new ArrayList<>();
        for (int row = radius; row < rows - radius; row++) {
            for (int col = radius; col < cols - radius; col++) {
                final float value = field[row * cols + col];
                if (Float.isNaN(value)) continue;

                boolean isHigh = true;
                boolean isLow = true;
                double extremeOpposite = value;
                boolean sawNaN = false;

                for (int dy = -radius; dy <= radius && (isHigh || isLow); dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        // A circle, not a square: the corners of a square are
                        // half again as far away, which biases the test.
                        if (dx * dx + dy * dy > radius * radius) continue;

                        final float other = field[(row + dy) * cols + (col + dx)];
                        if (Float.isNaN(other)) {
                            sawNaN = true;
                            break;
                        }
                        if (other > value) isHigh = false;
                        if (other < value) isLow = false;
                        if (!isHigh && !isLow) break;
                    }
                    if (sawNaN) break;
                }
                if (sawNaN || (!isHigh && !isLow)) continue;

                // Prominence against the ring at the search radius, which is the
                // furthest thing this centre had to beat.
                extremeOpposite = ringExtreme(field, cols, rows, col, row, radius, isHigh);
                if (Double.isNaN(extremeOpposite)) continue;
                if (Math.abs(value - extremeOpposite) < minProminence) continue;

                found.add(new Centre(col * (double) step, row * (double) step, value, isHigh));
            }
        }
        final List<Centre> spread =
                suppressNeighbours(found, CENTRE_RADIUS_PX * SUPPRESSION_FACTOR);
        return mergeAcrossWeakSaddles(spread, field, cols, rows, step, minProminence * 2);
    }

    /**
     * Joins two centres that turn out to be one system.
     *
     * <p>Distance cannot answer this. A broad ridge has several maxima strung
     * along it and is one high; two genuine highs can sit close together with a
     * col between them. What separates them is the ground in between: walk the
     * field from one centre to the other, and if it never drops a full contour
     * interval below the lower of the two, no isobar can close around either of
     * them on its own and a forecaster would draw one letter.</p>
     *
     * <p>This is the rule that removed the second H over the European ridge -
     * both read 1026, both sat inside the same 1024 isobar, and the lowest
     * point between them was less than a hectopascal down.</p>
     */
    private static List<Centre> mergeAcrossWeakSaddles(List<Centre> centres, float[] field,
                                                       int cols, int rows, int step,
                                                       double interval) {
        final List<Centre> kept = new ArrayList<>();
        for (Centre candidate : centres) {
            Centre absorbedBy = null;
            for (Centre other : kept) {
                if (other.high() != candidate.high()) continue;
                final double saddle =
                        saddleBetween(field, cols, rows, step, other, candidate);
                if (Double.isNaN(saddle)) continue;

                final double weaker = candidate.high()
                        ? Math.min(other.value(), candidate.value())
                        : Math.max(other.value(), candidate.value());
                if (Math.abs(weaker - saddle) < interval) {
                    absorbedBy = other;
                    break;
                }
            }
            if (absorbedBy == null) {
                kept.add(candidate);
            }
            else if (candidate.high() ? candidate.value() > absorbedBy.value()
                                      : candidate.value() < absorbedBy.value()) {
                // The one that is actually the centre of the system wins.
                kept.set(kept.indexOf(absorbedBy), candidate);
            }
        }
        return kept;
    }

    /** The most opposite value along the straight line between two centres. */
    private static double saddleBetween(float[] field, int cols, int rows, int step,
                                        Centre a, Centre b) {
        final int samples = (int) Math.max(8, Math.hypot(b.x() - a.x(), b.y() - a.y()) / step);
        double saddle = a.high() ? Double.MAX_VALUE : -Double.MAX_VALUE;
        boolean any = false;
        for (int i = 1; i < samples; i++) {
            final double t = i / (double) samples;
            final int col = (int) Math.round((a.x() + (b.x() - a.x()) * t) / step);
            final int row = (int) Math.round((a.y() + (b.y() - a.y()) * t) / step);
            if (col < 0 || row < 0 || col >= cols || row >= rows) continue;
            final float value = field[row * cols + col];
            if (Float.isNaN(value)) continue;
            any = true;
            saddle = a.high() ? Math.min(saddle, value) : Math.max(saddle, value);
        }
        return any ? saddle : Double.NaN;
    }

    /** The most opposite value on the ring at {@code radius}, or NaN. */
    private static double ringExtreme(float[] field, int cols, int rows,
                                      int col, int row, int radius, boolean high) {
        double extreme = high ? Double.MAX_VALUE : -Double.MAX_VALUE;
        boolean any = false;
        for (int i = 0; i < 32; i++) {
            final double angle = i * Math.PI / 16;
            final int x = col + (int) Math.round(Math.cos(angle) * radius);
            final int y = row + (int) Math.round(Math.sin(angle) * radius);
            if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
            final float value = field[y * cols + x];
            if (Float.isNaN(value)) continue;
            any = true;
            extreme = high ? Math.min(extreme, value) : Math.max(extreme, value);
        }
        return any ? extreme : Double.NaN;
    }

    /**
     * Keeps one marker per system.
     *
     * <p>A broad, flat centre satisfies the radius test at several adjacent
     * points, and two H's a few pixels apart is a rendering artefact rather than
     * two highs.</p>
     */
    private static List<Centre> suppressNeighbours(List<Centre> candidates, double minGap) {
        final List<Centre> kept = new ArrayList<>();
        for (Centre candidate : candidates) {
            boolean crowded = false;
            for (Centre other : kept) {
                if (Math.hypot(other.x() - candidate.x(), other.y() - candidate.y()) < minGap) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) kept.add(candidate);
        }
        return kept;
    }

    /** A separable box blur, run twice, which is close enough to a Gaussian. */
    private static float[] smooth(float[] field, int cols, int rows) {
        float[] out = field;
        for (int pass = 0; pass < 2; pass++) {
            out = blurAxis(out, cols, rows, true);
            out = blurAxis(out, cols, rows, false);
        }
        return out;
    }

    private static float[] blurAxis(float[] field, int cols, int rows, boolean horizontal) {
        final float[] out = new float[field.length];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                double total = 0;
                int count = 0;
                for (int d = -SMOOTHING_RADIUS; d <= SMOOTHING_RADIUS; d++) {
                    final int x = horizontal ? col + d : col;
                    final int y = horizontal ? row : row + d;
                    if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                    final float value = field[y * cols + x];
                    if (Float.isNaN(value)) continue;
                    total += value;
                    count++;
                }
                // A sample with no readable neighbours stays missing rather than
                // being invented, so the edge of the data keeps its shape.
                out[row * cols + col] = count == 0 ? Float.NaN : (float) (total / count);
            }
        }
        return out;
    }

    private String format(double level) {
        final double shown = level / style.labelScale();
        return Math.abs(shown - Math.rint(shown)) < 0.05
                ? String.valueOf(Math.round(shown))
                : String.format("%.1f", shown);
    }
}
