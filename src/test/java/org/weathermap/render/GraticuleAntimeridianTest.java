package org.weathermap.render;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.EquirectangularProjection;
import org.weathermap.model.MapProjection;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The meridian loop walks east from the west edge rather than counting up to
 * the east one. Counting up stopped before it started on a box whose east edge
 * is the smaller number, and a Pacific chart came out with no meridians at all.
 */
class GraticuleAntimeridianTest {

    /** How many image columns carry any gridline ink. */
    private static int meridianColumns(BoundingBox bounds) {
        final MapProjection projection = new EquirectangularProjection(bounds, 400, 200);
        final BufferedImage image =
                new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 400, 200);
            new GraticuleLayer().draw(g, projection);
        }
        finally {
            g.dispose();
        }

        // Only the top half, which is clear of the longitude labels along the
        // bottom edge and of every latitude line's own label at the left.
        int columns = 0;
        for (int x = 1; x < 400; x++) {
            for (int y = 1; y < 60; y++) {
                if (image.getRGB(x, y) != Color.WHITE.getRGB()) {
                    columns++;
                    break;
                }
            }
        }
        return columns;
    }

    @Test
    void drawsMeridiansAcrossABoxThatCrossesTheSeam() {
        final int columns = meridianColumns(BoundingBox.of(-10, 170, 10, -170));
        assertTrue(columns >= 3, "expected several meridians, found ink in "
                + columns + " column(s)");
    }

    @Test
    void drawsAsManyAsForTheSameWidthAwayFromTheSeam() {
        // Twenty degrees is twenty degrees; where they sit must not change how
        // many lines they earn.
        assertEquals(meridianColumns(BoundingBox.of(-10, 0, 10, 20)),
                     meridianColumns(BoundingBox.of(-10, 170, 10, -170)));
    }
}
