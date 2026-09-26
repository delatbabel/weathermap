package org.weathermap.tide;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Draws one day of tide as a flat chart: the curve, the turning points marked
 * on it, and the times and heights spelled out underneath.
 *
 * <h2>A picture, produced the same way the map is</h2>
 *
 * <p>This returns a {@link BufferedImage} rather than painting into a
 * component, for the reason the {@code Compositor} does: what is on the screen
 * and what gets copied or saved are then the same pixels, and cannot drift
 * apart. {@code TideWindow} scales this to fit and nothing else.</p>
 *
 * <p>And, like the map, it is <b>not themed</b>. It is a chart - it gets saved,
 * printed and read beside paper ones - so it stays a light document whatever
 * the window around it is doing.</p>
 *
 * <h2>The curve passes through the real turning points</h2>
 *
 * <p>The sea level arrives hourly and the highs and lows arrive separately, at
 * the minute. Drawing only the hourly samples would put every high water on the
 * hour and cut the top off it - on a two-metre range that is tens of
 * centimetres of error, visible as a flat-topped curve that disagrees with the
 * figures printed underneath. So the two sets are merged and the line is drawn
 * through both, which makes the peaks land where the numbers say they do.</p>
 */
public final class TideChart {

    // A flat palette, sharing the map's water colours so the two read as parts
    // of the same application rather than two programs in one window.
    private static final Color PAPER = new Color(255, 255, 255);
    private static final Color WATER_FILL = new Color(207, 227, 243);
    private static final Color WATER_LINE = new Color(52, 92, 132);
    private static final Color GRID = new Color(228, 231, 235);
    private static final Color AXIS_TEXT = new Color(120, 126, 134);
    private static final Color TITLE_TEXT = new Color(28, 30, 33);
    private static final Color SUBTITLE_TEXT = new Color(110, 116, 124);
    private static final Color HIGH_MARK = new Color(30, 96, 150);
    private static final Color LOW_MARK = new Color(120, 152, 180);
    private static final Color NOW_LINE = new Color(198, 84, 60);
    private static final Color RULE = new Color(234, 237, 240);

    /**
     * The proportions of a tide chart, which are not those of a map.
     *
     * <p>A day against a couple of metres wants to be wide and short: the
     * information is in when the water turns, and a tall chart spends its
     * height making a gentle curve look like a cliff.</p>
     */
    public static final double ASPECT = 16.0 / 9.0;

    /** Below this the axis labels and the footer stop fitting. In 16:9. */
    private static final int MIN_WIDTH = 480;
    private static final int MIN_HEIGHT = 270;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_TITLE =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");

    private TideChart() { }

    /**
     * The image size to draw at, given the output size the user has chosen.
     *
     * <p>Takes the width from the preferences and derives the height, rather
     * than using both: the stored size is the shape of a map, and a tide chart
     * in that shape is mostly empty sky.</p>
     */
    public static int[] fitSize(int maxWidth, int maxHeight) {
        int width = Math.max(MIN_WIDTH, maxWidth);
        int height = (int) Math.round(width / ASPECT);
        if (maxHeight > 0 && height > maxHeight) {
            height = maxHeight;
            width = (int) Math.round(height * ASPECT);
        }
        // The floor goes on both together, not on each as it is computed.
        // Clamping the height alone after the width had already been narrowed
        // to fit it produced an 18 by 270 image - the smallest size in neither
        // dimension and the right shape in none.
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            width = MIN_WIDTH;
            height = MIN_HEIGHT;
        }
        return new int[]{width, height};
    }

    /**
     * Draws the chart.
     *
     * @param data  the prediction, which may cover many days
     * @param day   the local day to draw
     * @param zone  the zone that decides where that day starts and ends, and
     *              which every time on the chart is written in
     * @param place where this is, and what it is called; may be null
     */
    public static BufferedImage render(TideData data, LocalDate day, ZoneId zone,
                                       TidePoint place, int width, int height) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                               RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                               RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                               RenderingHints.VALUE_STROKE_PURE);
            g.setColor(PAPER);
            g.fillRect(0, 0, width, height);

            final Layout layout = new Layout(width, height);
            drawHeading(g, layout, data, day, zone, place);

            final List<TideData.Extreme> extremes = data.extremesFor(day, zone);
            final List<TideData.Reading> curve = curveThrough(data, day, zone);
            if (curve.size() < 2) {
                drawNothing(g, layout);
                return image;
            }

            final Instant from = day.atStartOfDay(zone).toInstant();
            final Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
            final Scale scale = Scale.over(curve, extremes, layout.plot, from, to);

            drawGrid(g, layout, scale, from, to, zone);
            drawCurve(g, layout, scale, curve);
            drawNow(g, layout, scale, from, to);
            drawExtremes(g, layout, scale, extremes, zone);
            drawFooter(g, layout, extremes, zone, data.datum());
            return image;
        }
        finally {
            g.dispose();
        }
    }

    // ---- where everything goes -------------------------------------------

    /** The bands the chart is divided into, in pixels. */
    private static final class Layout {
        final int width;
        final int height;
        final Rectangle plot;
        final Rectangle footer;
        final float scale;

        Layout(int width, int height) {
            this.width = width;
            this.height = height;
            // Everything is sized off the width, so a chart drawn at 2400 px
            // for publication and one drawn at 800 px in the window are the
            // same picture rather than the same picture with different text on
            // it.
            this.scale = width / 960f;

            final int left = round(70 * scale);
            final int right = width - round(24 * scale);
            final int top = round(74 * scale);
            final int footerHeight = round(74 * scale);
            final int bottom = height - footerHeight - round(34 * scale);

            this.plot = new Rectangle(left, top, right - left, Math.max(1, bottom - top));
            this.footer = new Rectangle(left, height - footerHeight, right - left,
                                        footerHeight - round(10 * scale));
        }

        Font font(int style, float points) {
            return new Font(Font.SANS_SERIF, style, Math.max(8, Math.round(points * scale)));
        }

        static int round(float v) { return Math.round(v); }
    }

    /** Maps time and metres onto the plot rectangle. */
    private record Scale(Rectangle plot, Instant from, Instant to, double low, double high) {

        static Scale over(List<TideData.Reading> curve, List<TideData.Extreme> extremes,
                          Rectangle plot, Instant from, Instant to) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (TideData.Reading r : curve) {
                min = Math.min(min, r.metres());
                max = Math.max(max, r.metres());
            }
            for (TideData.Extreme e : extremes) {
                min = Math.min(min, e.metres());
                max = Math.max(max, e.metres());
            }
            // A flat day still needs an axis with two different numbers on it.
            if (max - min < 0.2) {
                final double middle = (max + min) / 2;
                min = middle - 0.1;
                max = middle + 0.1;
            }
            // Headroom for the labels that sit above the high water marks, and
            // a little below so the curve does not run along the frame.
            final double margin = (max - min) * 0.18;
            return new Scale(plot, from, to, min - margin * 0.6, max + margin);
        }

        double x(Instant when) {
            final double span = Duration.between(from, to).toSeconds();
            final double at = Duration.between(from, when).toSeconds();
            return plot.x + plot.width * (at / span);
        }

        double y(double metres) {
            return plot.y + plot.height * (1 - (metres - low) / (high - low));
        }
    }

    // ---- the curve --------------------------------------------------------

    /**
     * The hourly samples and the turning points, in one list in time order.
     *
     * <p>Merged rather than drawn as two things, so the line and the marks
     * cannot disagree: the dot on a high water sits on the curve because it
     * <em>is</em> one of the points the curve was drawn through.</p>
     */
    static List<TideData.Reading> curveThrough(TideData data, LocalDate day, ZoneId zone) {
        final List<TideData.Reading> out = new ArrayList<>(data.curveFor(day, zone));
        if (out.isEmpty()) return out;

        final Instant first = out.get(0).time();
        final Instant last = out.get(out.size() - 1).time();
        for (TideData.Extreme e : data.extremes()) {
            if (e.time().isBefore(first) || e.time().isAfter(last)) continue;
            out.add(new TideData.Reading(e.time(), e.metres()));
        }
        out.sort(Comparator.comparing(TideData.Reading::time));
        return out;
    }

    private static void drawCurve(Graphics2D g, Layout layout, Scale scale,
                                  List<TideData.Reading> curve) {
        final Path2D.Double line = new Path2D.Double();
        final double[] xs = new double[curve.size()];
        final double[] ys = new double[curve.size()];
        for (int i = 0; i < curve.size(); i++) {
            xs[i] = scale.x(curve.get(i).time());
            ys[i] = scale.y(curve.get(i).metres());
        }

        line.moveTo(xs[0], ys[0]);
        for (int i = 0; i < xs.length - 1; i++) {
            // Catmull-Rom through the samples, expressed as a Bezier. The tide
            // is a sum of sinusoids and joining hourly samples with straight
            // lines draws it as a polygon; the smoothing is cosmetic but the
            // shape it restores is the real one, and the points it passes
            // through are still measurements.
            final double x0 = xs[Math.max(0, i - 1)];
            final double y0 = ys[Math.max(0, i - 1)];
            final double x3 = xs[Math.min(xs.length - 1, i + 2)];
            final double y3 = ys[Math.min(ys.length - 1, i + 2)];
            line.curveTo(xs[i] + (xs[i + 1] - x0) / 6, ys[i] + (ys[i + 1] - y0) / 6,
                         xs[i + 1] - (x3 - xs[i]) / 6, ys[i + 1] - (y3 - ys[i]) / 6,
                         xs[i + 1], ys[i + 1]);
        }

        final java.awt.Shape clip = g.getClip();
        g.setClip(scale.plot());
        final Path2D.Double under = (Path2D.Double) line.clone();
        under.lineTo(xs[xs.length - 1], scale.plot().getMaxY());
        under.lineTo(xs[0], scale.plot().getMaxY());
        under.closePath();
        g.setColor(WATER_FILL);
        g.fill(under);

        g.setColor(WATER_LINE);
        g.setStroke(new BasicStroke(Math.max(1.4f, 2f * layout.scale),
                                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(line);
        g.setClip(clip);
    }

    // ---- frame, grid and axes ---------------------------------------------

    private static void drawGrid(Graphics2D g, Layout layout, Scale scale,
                                 Instant from, Instant to, ZoneId zone) {
        g.setFont(layout.font(Font.PLAIN, 11));
        final FontMetrics fm = g.getFontMetrics();
        g.setStroke(new BasicStroke(Math.max(0.8f, layout.scale)));

        // Heights: a line every metre while that gives a readable number of
        // them, otherwise every half or quarter.
        final double step = heightStep(scale.high() - scale.low());
        final double firstLine = Math.ceil(scale.low() / step) * step;
        for (double h = firstLine; h <= scale.high(); h += step) {
            final int y = (int) Math.round(scale.y(h));
            g.setColor(GRID);
            g.drawLine(scale.plot().x, y, scale.plot().x + scale.plot().width, y);
            g.setColor(AXIS_TEXT);
            final String label = String.format(Locale.ROOT, "%.1f m", h);
            g.drawString(label, scale.plot().x - fm.stringWidth(label) - Layout.round(8 * layout.scale),
                         y + fm.getAscent() / 2 - 1);
        }

        // Hours: every three, which is eight labels across a day - the same
        // density the height axis is aiming for.
        for (int hour = 0; hour <= 24; hour += 3) {
            final Instant at = from.plus(Duration.ofSeconds(
                    Math.round(Duration.between(from, to).toSeconds() * (hour / 24.0))));
            final int x = (int) Math.round(scale.x(at));
            if (hour > 0 && hour < 24) {
                g.setColor(GRID);
                g.drawLine(x, scale.plot().y, x, scale.plot().y + scale.plot().height);
            }
            g.setColor(AXIS_TEXT);
            final String label = String.format(Locale.ROOT, "%02d:00", hour % 24);
            g.drawString(label, x - fm.stringWidth(label) / 2,
                         scale.plot().y + scale.plot().height + fm.getAscent()
                                 + Layout.round(8 * layout.scale));
        }

        g.setColor(RULE);
        g.setStroke(new BasicStroke(Math.max(1f, layout.scale)));
        g.drawLine(scale.plot().x, scale.plot().y + scale.plot().height,
                   scale.plot().x + scale.plot().width, scale.plot().y + scale.plot().height);
    }

    /** A 1-2-5 style step that gives roughly four to eight gridlines. */
    static double heightStep(double span) {
        for (double step : new double[]{0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10}) {
            if (span / step <= 8) return step;
        }
        return 10;
    }

    /** A thin line at the present moment, when the day being drawn contains it. */
    private static void drawNow(Graphics2D g, Layout layout, Scale scale,
                                Instant from, Instant to) {
        final Instant now = Instant.now();
        if (now.isBefore(from) || !now.isBefore(to)) return;

        final int x = (int) Math.round(scale.x(now));
        g.setColor(NOW_LINE);
        g.setStroke(new BasicStroke(Math.max(1f, 1.2f * layout.scale), BasicStroke.CAP_BUTT,
                                    BasicStroke.JOIN_MITER, 10f,
                                    new float[]{4f * layout.scale, 4f * layout.scale}, 0f));
        g.drawLine(x, scale.plot().y, x, scale.plot().y + scale.plot().height);

        g.setFont(layout.font(Font.PLAIN, 10));
        g.drawString("now", x + Layout.round(4 * layout.scale),
                     scale.plot().y + g.getFontMetrics().getAscent());
    }

    // ---- the marks on the peaks -------------------------------------------

    private static void drawExtremes(Graphics2D g, Layout layout, Scale scale,
                                     List<TideData.Extreme> extremes, ZoneId zone) {
        final int radius = Math.max(3, Layout.round(4.5f * layout.scale));
        g.setFont(layout.font(Font.BOLD, 11));
        final FontMetrics fm = g.getFontMetrics();

        for (TideData.Extreme e : extremes) {
            final double x = scale.x(e.time());
            final double y = scale.y(e.metres());
            if (x < scale.plot().x || x > scale.plot().getMaxX()) continue;

            g.setColor(e.isHigh() ? HIGH_MARK : LOW_MARK);
            g.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
            g.setColor(PAPER);
            g.setStroke(new BasicStroke(Math.max(1f, 1.5f * layout.scale)));
            g.draw(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));

            // Above a high and below a low, which is the way round that keeps
            // the label off the curve rather than on top of it.
            final String label = String.format(Locale.ROOT, "%s  %.2f m",
                    TIME.format(e.time().atZone(zone)), e.metres());
            final float textX = (float) Math.min(
                    Math.max(scale.plot().x, x - fm.stringWidth(label) / 2.0),
                    scale.plot().getMaxX() - fm.stringWidth(label));

            final int gap = Layout.round(6 * layout.scale);
            final float above = (float) (y - radius - gap);
            final float below = (float) (y + radius + fm.getAscent() + gap);

            // ...unless there is no room on that side. The lowest low of the
            // day sits near the floor of the plot by construction, and its
            // label went through the axis and out of the chart; a label that
            // has to change sides is better than one that is cut in half.
            float textY = e.isHigh() ? above : below;
            if (textY - fm.getAscent() < scale.plot().y) textY = below;
            if (textY > scale.plot().getMaxY() - Layout.round(2 * layout.scale)) textY = above;

            g.setColor(e.isHigh() ? HIGH_MARK : WATER_LINE);
            g.drawString(label, textX, textY);
        }
    }

    // ---- words --------------------------------------------------------------

    private static void drawHeading(Graphics2D g, Layout layout, TideData data,
                                    LocalDate day, ZoneId zone, TidePoint place) {
        final int left = layout.plot.x;
        g.setFont(layout.font(Font.BOLD, 17));
        g.setColor(TITLE_TEXT);
        final String title = (place == null)
                ? "Tides for " + DAY_TITLE.format(day)
                : place.title() + " — " + DAY_TITLE.format(day);
        g.drawString(title, left, Layout.round(32 * layout.scale));

        g.setFont(layout.font(Font.PLAIN, 11));
        g.setColor(SUBTITLE_TEXT);
        final StringBuilder sub = new StringBuilder();
        // The coordinates go on the chart even when there is a name above
        // them. A name is what the reader recognises the place by and the
        // position is what was actually asked for; a saved chart that carries
        // only the first cannot be checked or repeated.
        if (place != null && place.isNamed()) sub.append(place.coordinates()).append("  ·  ");
        if (data.station() != null && !Double.isNaN(data.station().distanceKm())) {
            sub.append(data.station().describe()).append("  ·  ");
        }
        sub.append("heights relative to ").append(data.datum())
           .append("  ·  times in ").append(zone.getId());
        g.drawString(sub.toString(), left, Layout.round(51 * layout.scale));
    }

    /**
     * The figures under the chart: one card per turning point.
     *
     * <p>They are on the chart rather than beside it in the window because
     * they are part of what gets saved and pasted. A chart whose numbers only
     * exist in the application is a chart that loses them the moment anyone
     * shares it.</p>
     */
    private static void drawFooter(Graphics2D g, Layout layout,
                                   List<TideData.Extreme> extremes, ZoneId zone, String datum) {
        final Rectangle box = layout.footer;
        g.setColor(RULE);
        g.setStroke(new BasicStroke(Math.max(1f, layout.scale)));
        g.drawLine(box.x, box.y, box.x + box.width, box.y);

        if (extremes.isEmpty()) {
            g.setFont(layout.font(Font.PLAIN, 12));
            g.setColor(SUBTITLE_TEXT);
            g.drawString("No high or low water on this day.",
                         box.x, box.y + Layout.round(26 * layout.scale));
            return;
        }

        final int cells = extremes.size();
        final double cellWidth = (double) box.width / cells;
        for (int i = 0; i < cells; i++) {
            final TideData.Extreme e = extremes.get(i);
            final int x = box.x + (int) Math.round(i * cellWidth);

            if (i > 0) {
                g.setColor(RULE);
                g.drawLine(x, box.y + Layout.round(12 * layout.scale),
                           x, box.y + box.height - Layout.round(6 * layout.scale));
            }

            final int textX = x + Layout.round(14 * layout.scale);
            g.setFont(layout.font(Font.PLAIN, 11));
            g.setColor(e.isHigh() ? HIGH_MARK : LOW_MARK);
            g.drawString((e.isHigh() ? "▲ " : "▼ ") + e.kind().displayName() + " tide",
                         textX, box.y + Layout.round(24 * layout.scale));

            g.setFont(layout.font(Font.BOLD, 16));
            g.setColor(TITLE_TEXT);
            final String time = TIME.format(e.time().atZone(zone));
            g.drawString(time, textX, box.y + Layout.round(47 * layout.scale));

            g.setFont(layout.font(Font.PLAIN, 13));
            g.setColor(SUBTITLE_TEXT);
            g.drawString(String.format(Locale.ROOT, "%.2f m", e.metres()),
                         textX + g.getFontMetrics(layout.font(Font.BOLD, 16)).stringWidth(time)
                                 + Layout.round(10 * layout.scale),
                         box.y + Layout.round(47 * layout.scale));
        }
    }

    private static void drawNothing(Graphics2D g, Layout layout) {
        g.setFont(layout.font(Font.PLAIN, 13));
        g.setColor(SUBTITLE_TEXT);
        final String message = "No tide data for this day.";
        g.drawString(message,
                     layout.plot.x + (layout.plot.width
                             - g.getFontMetrics().stringWidth(message)) / 2,
                     layout.plot.y + layout.plot.height / 2);
    }
}
