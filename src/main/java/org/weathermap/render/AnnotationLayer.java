package org.weathermap.render;

import org.weathermap.grib.Grid;
import org.weathermap.model.MapProjection;
import org.weathermap.model.RenderSpec;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The furniture: a colour-ramp legend, the model and valid time, and an
 * attribution line.
 *
 * <p><b>The attribution is not optional.</b> OpenStreetMap data is ODbL, which
 * requires the source to be credited on anything produced from it. A composited
 * PNG that leaves the building without "© OpenStreetMap contributors" on it is a
 * licence breach, so this layer draws it whenever
 * {@link RenderSpec.LayerKind#ANNOTATION} is on, and the README says not to turn
 * it off for anything published.</p>
 */
public final class AnnotationLayer implements Layer {

    /**
     * How the two times on the chart are written.
     *
     * <p>The day of the week is there on purpose. "13:00 on the 13th" is a
     * date; "Sunday 13:00" is when you are going to be out in it, and a
     * forecast is read to answer the second question.</p>
     */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy  HH:mm");



    public static final String OSM_ATTRIBUTION = "Map data © OpenStreetMap contributors (ODbL)";
    public static final String NOAA_ATTRIBUTION = "Forecast data: NOAA/NCEP";

    private final Grid grid;
    private final ColourRamp ramp;
    private final String modelName;
    private final boolean hasWind;
    private final java.time.Instant validTime;
    private final List<String> attributions;

    /** One of the isoline layers, for the caption; null when none are drawn. */
    private final IsolineLayer isolines;

    /** When the chart was rendered. */
    private final java.time.Instant createdAt;

    /** The zone both times are written in. */
    private final java.time.ZoneId zone;

    public AnnotationLayer(Grid grid, ColourRamp ramp, String modelName) {
        this(grid, ramp, modelName, false);
    }

    public AnnotationLayer(Grid grid, ColourRamp ramp, String modelName, boolean hasWind) {
        this(grid, ramp, modelName, hasWind, grid == null ? null : grid.validTime());
    }

    /**
     * @param grid    the field the legend describes, or {@code null}
     * @param hasWind whether barbs are on the map, so the title can say so -
     *                a chart captioned "Temperature" whose subject is plainly
     *                the wind misdescribes itself
     * @param validTime the instant the chart depicts, which is not always the
     *                primary field's own: an accumulation reports the start of
     *                its window. See {@link Compositor#validTimeOf}.
     */
    public AnnotationLayer(Grid grid, ColourRamp ramp, String modelName, boolean hasWind,
                           java.time.Instant validTime) {
        this(grid, ramp, modelName, hasWind, validTime, List.of(OSM_ATTRIBUTION));
    }

    /**
     * @param attributions the map-data credits to print, in order; the forecast
     *                     credit is always added after them
     */
    public AnnotationLayer(Grid grid, ColourRamp ramp, String modelName, boolean hasWind,
                           java.time.Instant validTime, List<String> attributions) {
        this(grid, ramp, modelName, hasWind, validTime, attributions, null);
    }

    /**
     * @param isolines one of the isoline layers, so the caption can give the
     *                 contour interval; {@code null} when none are drawn
     */
    public AnnotationLayer(Grid grid, ColourRamp ramp, String modelName, boolean hasWind,
                           java.time.Instant validTime, List<String> attributions,
                           IsolineLayer isolines) {
        this(grid, ramp, modelName, hasWind, validTime, attributions, isolines,
             java.time.Instant.now(), java.time.ZoneId.systemDefault());
    }

    /**
     * @param createdAt when the chart was rendered
     * @param zone      the zone both times are written in
     */
    public AnnotationLayer(Grid grid, ColourRamp ramp, String modelName, boolean hasWind,
                           java.time.Instant validTime, List<String> attributions,
                           IsolineLayer isolines, java.time.Instant createdAt,
                           java.time.ZoneId zone) {
        this.grid = grid;
        this.ramp = ramp;
        this.modelName = modelName;
        this.hasWind = hasWind;
        this.validTime = validTime;
        this.attributions = List.copyOf(attributions);
        this.isolines = isolines;
        this.createdAt = createdAt;
        this.zone = (zone == null) ? java.time.ZoneId.systemDefault() : zone;
    }

    @Override
    public void draw(Graphics2D g, MapProjection projection) {
        final int w = projection.imageWidth();
        final int h = projection.imageHeight();

        drawTitle(g, w);
        drawTimes(g, w);
        // A legend explains a colour ramp, so it is drawn only when something
        // was painted with one. A chart of wind and pressure has no wash on it
        // and used to carry a legend for a ramp nothing was drawn in.
        if (grid != null && ramp != null && Compositor.isFilled(grid.variable().style())) {
            drawLegend(g, w, h);
        }
        drawAttribution(g, w, h);
    }

    private void drawTitle(Graphics2D g, int width) {
        if (grid == null) return;
        final StringBuilder subject = new StringBuilder();
        if (hasWind) subject.append("Wind");
        if (!FieldStyle.isVectorComponent(grid.variable())) {
            if (subject.length() > 0) subject.append(" + ");
            subject.append(grid.variable().displayName())
                   .append(" @ ").append(grid.level().displayName());
        }
        // The contour interval belongs on the chart, not in the documentation.
        // Isobar spacing is read as a gradient, and a reader cannot do that
        // without knowing what one gap is worth.
        if (isolines != null && isolines.grid() != grid) {
            subject.append(" + ").append(isolines.grid().variable().displayName());
        }
        final String isobarNote = isolines == null ? ""
                : String.format("  ·  %s every %s %s",
                        isolines.grid().variable().displayName().toLowerCase(java.util.Locale.ROOT)
                                .contains("pressure") ? "isobars" : "contours",
                        trim(isolines.labelledInterval()), isolines.labelUnit());
        // The valid time used to live here, small and always in UTC. It is now
        // a label of its own, and having it in two places in two zones would be
        // two answers to one question.
        final String line = modelName + "  ·  " + subject + isobarNote;
        g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
        final FontMetrics fm = g.getFontMetrics();
        final int pad = 8;

        g.setColor(new Color(255, 255, 255, 210));
        g.fill(new Rectangle2D.Double(0, 0, width, fm.getHeight() + pad));
        g.setColor(new Color(20, 20, 20));
        g.drawString(line, pad, fm.getAscent() + pad / 2);
    }

    private void drawLegend(Graphics2D g, int width, int height) {
        final int barWidth = Math.min(280, width - 40);
        final int barHeight = 12;
        final int x = width - barWidth - 20;
        final int y = height - 46;

        // The legend sits over the label layer, so it needs its own ground -
        // without one the place names show through the numbers and the ramp
        // name, which is where this was unreadable before.
        g.setColor(new Color(255, 255, 255, 225));
        g.fill(new Rectangle2D.Double(x - 8, y - 18, barWidth + 16, barHeight + 34));

        // Sample the ramp at both ends plus the interior stops so the gradient
        // shows the ramp's real shape rather than a straight min-to-max blend.
        final float lo = ramp.min();
        final float hi = ramp.max();
        final int steps = 8;
        final float[] fractions = new float[steps];
        final Color[] colours = new Color[steps];
        for (int i = 0; i < steps; i++) {
            fractions[i] = i / (float) (steps - 1);
            colours[i] = ramp.colourFor(lo + (hi - lo) * fractions[i]);
        }

        g.setPaint(new LinearGradientPaint(
                new Point2D.Float(x, y), new Point2D.Float(x + barWidth, y), fractions, colours));
        g.fill(new Rectangle2D.Double(x, y, barWidth, barHeight));
        g.setPaint(new Color(40, 40, 40));
        g.setStroke(new BasicStroke(1f));
        g.draw(new Rectangle2D.Double(x, y, barWidth, barHeight));

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
        final FontMetrics fm = g.getFontMetrics();
        g.drawString(fmt(lo), x, y + barHeight + fm.getAscent() + 2);
        final String hiLabel = fmt(hi);
        g.drawString(hiLabel, x + barWidth - fm.stringWidth(hiLabel), y + barHeight + fm.getAscent() + 2);
        g.drawString(ramp.name(), x, y - 3);
    }

    /**
     * The two times, large enough to read from across a room.
     *
     * <p>Separate from the title and much bigger than it, because they are what
     * the chart is checked against: whether this is the forecast for the
     * crossing tomorrow, and whether it was made before or after the last
     * model run. A chart whose age is not obvious gets trusted long after it
     * should have been thrown away.</p>
     *
     * <p>Top right, under the title bar - the one corner with no legend, no
     * attribution and no graticule labels in it.</p>
     */
    private void drawTimes(Graphics2D g, int width) {
        if (validTime == null && createdAt == null) return;

        final Font captionFont = g.getFont().deriveFont(Font.BOLD, 11f);
        final Font timeFont = g.getFont().deriveFont(Font.BOLD, 20f);

        g.setFont(captionFont);
        final FontMetrics captionMetrics = g.getFontMetrics();
        g.setFont(timeFont);
        final FontMetrics timeMetrics = g.getFontMetrics();

        final String validText = stamp(validTime);
        final String createdText = stamp(createdAt);

        final int pad = 10;
        final int gap = 6;
        int textWidth = 0;
        if (validText != null) textWidth = Math.max(textWidth, timeMetrics.stringWidth(validText));
        if (createdText != null) {
            textWidth = Math.max(textWidth, timeMetrics.stringWidth(createdText));
        }
        textWidth = Math.max(textWidth, captionMetrics.stringWidth("CHART CREATED"));

        int rows = 0;
        if (validText != null) rows++;
        if (createdText != null) rows++;
        final int rowHeight = captionMetrics.getHeight() + timeMetrics.getHeight();
        final int boxHeight = rows * rowHeight + (rows - 1) * gap + pad * 2;
        final int boxWidth = textWidth + pad * 2;

        final int top = titleHeight(g) + 10;
        final int left = width - boxWidth - 10;

        // An opaque-enough plate: these sit over wind barbs, and a time that has
        // to be picked out of a field of them is not the readable label that was
        // asked for.
        g.setColor(new Color(255, 255, 255, 232));
        g.fill(new java.awt.geom.RoundRectangle2D.Double(left, top, boxWidth, boxHeight, 10, 10));
        g.setColor(new Color(120, 120, 126, 150));
        g.draw(new java.awt.geom.RoundRectangle2D.Double(left, top, boxWidth, boxHeight, 10, 10));

        int y = top + pad;
        if (validText != null) {
            y = drawStamp(g, "VALID AT", validText, left + pad, y,
                          captionFont, timeFont, new Color(20, 20, 20));
            y += gap;
        }
        if (createdText != null) {
            // Greyer than the valid time: it matters, but never more than the
            // time the forecast is for.
            drawStamp(g, "CHART CREATED", createdText, left + pad, y,
                      captionFont, timeFont, new Color(95, 95, 100));
        }
    }

    /** One instant, written in the chart's zone and named. */
    private String stamp(java.time.Instant instant) {
        if (instant == null) return null;
        return STAMP.format(instant.atZone(zone)) + " " + zoneLabel(zone, instant);
    }

    /**
     * What to call the zone on the chart: {@code GMT+7}, or {@code UTC} at zero.
     *
     * <p>Always the offset, never the abbreviation, even where the platform has
     * one. Partly for consistency - a set of charts where some say "BST" and
     * others "+07" reads as two different conventions - but mainly because the
     * abbreviations are not unique. BST is British Summer Time and Bangladesh
     * Standard Time; IST is India, Ireland and Israel. On a chart that may be
     * read anywhere, an offset is the only label that cannot be misread, and
     * it is shorter than the name it replaces.</p>
     *
     * <p>Resolved against the instant rather than once for the chart, because
     * the two times can straddle a clock change and a chart that labels both
     * with one offset is an hour wrong about one of them on the single night
     * anyone would check.</p>
     */
    static String zoneLabel(java.time.ZoneId zone, java.time.Instant instant) {
        final java.time.ZoneOffset offset = zone.getRules().getOffset(instant);
        final int seconds = offset.getTotalSeconds();
        if (seconds == 0) return "UTC";

        final int hours = Math.abs(seconds) / 3600;
        final int minutes = (Math.abs(seconds) % 3600) / 60;
        final String sign = seconds < 0 ? "-" : "+";
        // Half-hour and three-quarter-hour zones are real - India, Nepal,
        // Chatham - so the minutes are kept when there are any and dropped when
        // there are not.
        return minutes == 0
                ? "GMT" + sign + hours
                : String.format("GMT%s%d:%02d", sign, hours, minutes);
    }

    private int drawStamp(Graphics2D g, String caption, String value, int x, int y,
                          Font captionFont, Font timeFont, Color colour) {
        g.setFont(captionFont);
        final FontMetrics captionMetrics = g.getFontMetrics();
        g.setColor(new Color(120, 120, 126));
        g.drawString(caption, x, y + captionMetrics.getAscent());

        g.setFont(timeFont);
        final FontMetrics timeMetrics = g.getFontMetrics();
        g.setColor(colour);
        g.drawString(value, x, y + captionMetrics.getHeight() + timeMetrics.getAscent());

        return y + captionMetrics.getHeight() + timeMetrics.getHeight();
    }

    private int titleHeight(Graphics2D g) {
        final Font saved = g.getFont();
        g.setFont(saved.deriveFont(Font.BOLD, 13f));
        final int height = g.getFontMetrics().getHeight() + 8;
        g.setFont(saved);
        return height;
    }

    private static String trim(double value) {
        return Math.abs(value - Math.rint(value)) < 0.05
                ? String.valueOf(Math.round(value))
                : String.format("%.1f", value);
    }

    private void drawAttribution(Graphics2D g, int width, int height) {
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
        final FontMetrics fm = g.getFontMetrics();
        final List<String> credits = new java.util.ArrayList<>(attributions);
        credits.add(NOAA_ATTRIBUTION);
        final String line = String.join("  ·  ", credits);
        final int textWidth = fm.stringWidth(line);
        final int pad = 4;

        g.setColor(new Color(255, 255, 255, 200));
        g.fill(new Rectangle2D.Double(0, height - fm.getHeight() - pad,
                                      textWidth + pad * 2, fm.getHeight() + pad));
        g.setColor(new Color(40, 40, 40));
        g.drawString(line, pad, height - pad - fm.getDescent());
    }

    private static String fmt(float v) {
        return (Math.abs(v) >= 100)
                ? String.format(java.util.Locale.ROOT, "%.0f", v)
                : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    @Override
    public RenderSpec.LayerKind kind() { return RenderSpec.LayerKind.ANNOTATION; }
}
