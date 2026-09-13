package org.weathermap.instagram;

import org.weathermap.MapService;
import org.weathermap.model.InstagramAccount;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Writes charts where Instagram can fetch them.
 *
 * <h2>Why the charts are not posted as they are</h2>
 *
 * <p>Two reasons, both of them the API's:</p>
 *
 * <ul>
 *   <li><b>Carousels are JPEG only.</b> The application writes PNG, which is
 *       right for a chart - lossless, sharp lines, small for flat colour - and
 *       rejected here. So each is re-encoded, at high quality, onto an opaque
 *       white ground because JPEG has no alpha.</li>
 *   <li><b>The images must be reachable from the internet.</b> Meta fetches
 *       every URL itself; there is no upload. So they are written into the
 *       account's publish directory, whose public address the user has
 *       configured.</li>
 * </ul>
 *
 * <p>Names include the valid time and a run stamp, so a second post does not
 * overwrite images the first one is still being fetched for.</p>
 */
public final class ChartPublisher {

    private static final Logger LOG = Logger.getLogger(ChartPublisher.class.getName());

    /** High enough that the isobar labels and the barb feathers survive. */
    private static final float JPEG_QUALITY = 0.92f;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'").withZone(ZoneId.of("UTC"));

    private final InstagramAccount account;

    public ChartPublisher(InstagramAccount account) {
        this.account = account;
    }

    /**
     * The charts a post should contain: the one on screen, then the ones after
     * it in time order.
     *
     * <p>Chronological because a forecast read backwards is not a forecast, and
     * because Instagram shows a carousel in the order it is given. Fewer than
     * asked for is returned near the end of a series rather than wrapping around
     * to the beginning, which would put next Tuesday between two Fridays.</p>
     *
     * @param series  the whole series, in time order
     * @param current the index on screen
     * @param count   how many charts to post, including the current one
     */
    public static List<MapService.Result> selectFrom(List<MapService.Result> series,
                                                     int current, int count) {
        final List<MapService.Result> out = new ArrayList<>();
        for (int i = current; i < series.size() && out.size() < count; i++) {
            out.add(series.get(i));
        }
        return out;
    }

    /**
     * Writes each chart as a JPEG and returns what to post.
     *
     * @throws IOException if the publish directory cannot be written
     */
    public List<InstagramClient.CarouselImage> publish(List<MapService.Result> charts)
            throws IOException {

        Files.createDirectories(account.publishDir());
        final String run = STAMP.format(java.time.Instant.now());

        final List<InstagramClient.CarouselImage> out = new ArrayList<>();
        for (int i = 0; i < charts.size(); i++) {
            final MapService.Result chart = charts.get(i);
            final String name = "chart-" + run + "-" + (i + 1) + ".jpg";
            final Path file = account.publishDir().resolve(name);

            writeJpeg(chart.image(), file);
            LOG.info(() -> "wrote " + file + " for " + account.urlFor(name));
            out.add(new InstagramClient.CarouselImage(account.urlFor(name), altTextFor(chart)));
        }
        return out;
    }

    /** What a screen reader is told, and what Instagram stores as alt text. */
    static String altTextFor(MapService.Result chart) {
        final String when = chart.validTime() == null ? "an unknown time"
                : DateTimeFormatter.ofPattern("EEE d MMM HH:mm 'UTC'")
                        .withZone(ZoneId.of("UTC")).format(chart.validTime());
        return "Weather chart valid " + when
                + ": wind barbs, isobars and a coastline base map.";
    }

    /**
     * JPEG at a quality that keeps the chart readable.
     *
     * <p>Written through an {@link ImageWriter} rather than
     * {@code ImageIO.write}, because the default quality turns isobar labels and
     * barb feathers into mush - fine lines on flat colour are the worst case for
     * the format, and this chart is almost entirely fine lines on flat
     * colour.</p>
     */
    static void writeJpeg(BufferedImage source, Path file) throws IOException {
        // JPEG has no alpha; without an explicit ground, transparency comes out
        // black rather than white.
        final BufferedImage opaque = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = opaque.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
            g.drawImage(source, 0, 0, null);
        }
        finally {
            g.dispose();
        }

        final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(Files.newOutputStream(file))) {
            writer.setOutput(out);
            final ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(opaque, null, null), params);
        }
        finally {
            writer.dispose();
        }
    }
}
