package org.weathermap.render;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.EquirectangularProjection;
import org.weathermap.model.MapProjection;
import org.weathermap.osm.Feature;
import org.weathermap.osm.FeatureKind;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A way on the far side of the world must not be drawn across the chart.
 *
 * <p>Longitude is cyclic and the projection has to cut the circle somewhere.
 * A way stepping over that cut comes back as one point at the extreme left and
 * the next at the extreme right, and the straight line between them crosses the
 * picture. That is what a chart of the Indian Ocean grew three of - from South
 * American coastline - the moment the projection learned to wrap.</p>
 */
class VectorLayersWrapTest {

    /** How many pixels of the given row are not background. */
    private static int inkAcross(BoundingBox bounds, Feature feature, int row) {
        final MapProjection projection = new EquirectangularProjection(bounds, 400, 200);
        final BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 400, 200);
            new VectorLayers.CoastlineLayer(List.of(feature), 1f).draw(g, projection);
        }
        finally {
            g.dispose();
        }

        int ink = 0;
        for (int x = 0; x < 400; x++) {
            if (image.getRGB(x, row) != Color.WHITE.getRGB()) ink++;
        }
        return ink;
    }

    private static Feature way(double... lons) {
        final List<double[]> points = new java.util.ArrayList<>();
        for (double lon : lons) points.add(new double[]{0, lon});
        return new Feature(FeatureKind.COASTLINE, points, Map.of("natural", "coastline"));
    }

    @Test
    void awayOnTheFarSideOfTheWorldLeavesNoInk() {
        // Asia, 40 to 140 east. The cut falls opposite its middle, at 90 west,
        // and this way steps straight over it.
        assertEquals(0, inkAcross(BoundingBox.of(-30, 40, 30, 140), way(-80, -100), 100));
    }

    @Test
    void andNoneOnAChartThatItselfCrossesTheSeam() {
        // The Pacific, centred on the antimeridian, so the cut is the prime
        // meridian and it is European coastline that would be dragged across.
        assertEquals(0, inkAcross(BoundingBox.of(-30, 140, 30, -120), way(-10, 10), 100));
    }

    @Test
    void awayInsideTheBoxIsStillDrawn() {
        // The guard must not have been bought by drawing nothing at all.
        final int ink = inkAcross(BoundingBox.of(-30, 140, 30, -120), way(170, -170), 100);
        assertEquals(80, ink, 2, "twenty degrees of a hundred, across 400 pixels");
    }
}
