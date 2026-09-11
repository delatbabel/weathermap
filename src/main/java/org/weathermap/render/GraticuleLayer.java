package org.weathermap.render;

import org.weathermap.model.BoundingBox;
import org.weathermap.model.MapProjection;
import org.weathermap.model.RenderSpec;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;

/**
 * Lat/lon gridlines with edge labels.
 *
 * <p>The interval is chosen from the span so the count stays readable at any
 * zoom - roughly six to ten lines each way - stepping through a 1-2-5 sequence
 * rather than dividing the span, which would give lines at arbitrary
 * fractions of a degree.</p>
 */
public final class GraticuleLayer implements Layer {

    private static final double[] STEPS =
            {0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 20, 30};

    private final Color colour = new Color(120, 120, 120, 90);

    @Override
    public void draw(Graphics2D g, MapProjection projection) {
        final BoundingBox b = projection.bounds();
        final double latStep = chooseStep(b.heightDegrees());
        final double lonStep = chooseStep(b.widthDegrees());

        g.setColor(colour);
        g.setStroke(new BasicStroke(0.7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                    10f, new float[]{3f, 5f}, 0f));
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));

        for (double lat = Math.ceil(b.south() / latStep) * latStep; lat <= b.north(); lat += latStep) {
            final Point2D.Double a = projection.toPixel(lat, b.west());
            final Point2D.Double c = projection.toPixel(lat, b.east());
            g.draw(new Line2D.Double(a.x, a.y, c.x, c.y));
            g.drawString(label(lat, 'N', 'S'), 3, (float) a.y - 2);
        }
        for (double lon = Math.ceil(b.west() / lonStep) * lonStep; lon <= b.east(); lon += lonStep) {
            final Point2D.Double a = projection.toPixel(b.north(), lon);
            final Point2D.Double c = projection.toPixel(b.south(), lon);
            g.draw(new Line2D.Double(a.x, a.y, c.x, c.y));
            g.drawString(label(lon, 'E', 'W'), (float) a.x + 2, projection.imageHeight() - 3);
        }
    }

    private static double chooseStep(double span) {
        for (double step : STEPS) {
            if (span / step <= 10) return step;
        }
        return STEPS[STEPS.length - 1];
    }

    private static String label(double value, char positive, char negative) {
        final char hemisphere = value >= 0 ? positive : negative;
        final double magnitude = Math.abs(value);
        return (magnitude == Math.floor(magnitude)
                ? String.format(java.util.Locale.ROOT, "%.0f", magnitude)
                : String.format(java.util.Locale.ROOT, "%.2f", magnitude)) + "°" + hemisphere;
    }

    @Override
    public RenderSpec.LayerKind kind() { return RenderSpec.LayerKind.GRATICULE; }
}
