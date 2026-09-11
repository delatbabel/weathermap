package org.weathermap.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * Writes a composited map to a PNG.
 *
 * <p>Written through a {@code .part} file and moved into place, for the same
 * reason every other writer in this project is: a half-written PNG is a file
 * that looks present and opens broken, and the CLI is expected to run
 * unattended from cron.</p>
 */
public final class PngWriter {

    private static final Logger LOG = Logger.getLogger(PngWriter.class.getName());

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'").withZone(ZoneOffset.UTC);

    private PngWriter() { }

    /** @return the file written */
    public static Path write(BufferedImage image, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        final Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            if (!ImageIO.write(image, "png", part.toFile())) {
                throw new IOException("no PNG writer is registered - a headless JRE without "
                        + "the standard image plugins?");
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            LOG.info(() -> "wrote " + target + " (" + image.getWidth()
                    + "x" + image.getHeight() + ")");
            return target;
        }
        finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * Builds a stable, sortable file name.
     *
     * <p>Named after the <em>valid</em> time rather than the run time, because
     * that is what a reader of the map cares about and what makes a directory of
     * them sort into a sequence.</p>
     *
     * @return e.g. {@code gfs_0p25-TMP-2m-20260911T1200Z.png}
     */
    public static String fileName(String modelId, String variableCode,
                                  String levelCode, Instant validTime) {
        return sanitise(modelId) + "-" + sanitise(variableCode) + "-"
                + sanitise(shortLevel(levelCode)) + "-" + STAMP.format(validTime) + ".png";
    }

    private static String shortLevel(String levelCode) {
        return switch (levelCode) {
            case "2_m_above_ground" -> "2m";
            case "10_m_above_ground" -> "10m";
            case "mean_sea_level" -> "msl";
            case "entire_atmosphere" -> "atm";
            default -> levelCode;
        };
    }

    private static String sanitise(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
