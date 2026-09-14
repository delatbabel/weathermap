package org.weathermap.render;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.MercatorProjection;
import org.weathermap.osm.WorldGazetteer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The names have to be readable and there have to be enough of them.
 *
 * <p>Counted from the dots rather than the text: a city label draws one, they
 * are a single flat colour, and counting them says how many names the map
 * actually carries without anything having to read the pixels as words.</p>
 */
class PlaceLabelLayerTest {

    /** The area the charts in use actually cover. */
    private static final BoundingBox SOUTHEAST_ASIA =
            BoundingBox.of(-8.50355, 85.63097, 26.96360, 141.84170);

    private static final Color DOT = new Color(60, 60, 60);

    private static BufferedImage render(int width, int height) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        // Off, so a dot is exactly its own colour and can be counted.
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                           RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        new VectorLayers.PlaceLabelLayer(WorldGazetteer.features())
                .draw(g, new MercatorProjection(SOUTHEAST_ASIA, width, height));
        g.dispose();
        return image;
    }

    private static int dotPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == (DOT.getRGB() & 0xFFFFFF)) count++;
            }
        }
        return count;
    }

    /**
     * Doubling the names did not halve how many of them there are.
     *
     * <p>It nearly did. Bigger labels collide more, and the first attempt lost
     * Bangkok, Hanoi, Jakarta and Ho Chi Minh City to names that had been drawn
     * before them - which is the wrong trade, since the whole reason for the
     * larger text is to be read. Two things bought the room back: the margin
     * around a label stayed where it was rather than doubling with the text,
     * and a name blocked on the right of its dot is now tried on the other
     * three sides before being given up.</p>
     *
     * <p>The floor is well under what it renders today, so this fails on a real
     * regression rather than on a name or two moving.</p>
     */
    @Test
    void aChartOfSoutheastAsiaStillCarriesPlentyOfNames() {
        final BufferedImage image = render(1600, 1200);
        final int dots = dotPixels(image);

        // Each dot is DOT_DIAMETER squared at most, so this is a floor on the
        // count of them rather than an exact number.
        final int perDot = 8 * 8;
        assertTrue(dots / perDot >= 25,
                "only about " + (dots / perDot) + " city labels were placed on a "
                + "1600x1200 chart - the names have crowded each other out");
    }

    /** A label has to be big enough to read on a phone, which is where these go. */
    @Test
    void theNamesAreDrawnLargeEnoughToRead() {
        final BufferedImage small = render(1600, 1200);

        // Ink is a proxy for size: the same names at half the point size cover
        // roughly half the area, so this catches the font quietly going back to
        // what it was. It renders about 42,000 today - the floor is well below
        // that, since a name or two moving should not fail a build.
        int ink = 0;
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                if ((small.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) ink++;
            }
        }
        assertTrue(ink > 30_000,
                "the labels cover only " + ink + " pixels of a 1.9 million pixel chart");
    }
}
