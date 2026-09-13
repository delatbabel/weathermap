package org.weathermap.gui;

import org.weathermap.model.InstagramAccount;
import org.weathermap.util.Http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Confirms that the publish folder really is served at the public URL.
 *
 * <p>This is the one part of the setup a user cannot check by looking. The
 * folder exists, the URL resolves, and the two still have nothing to do with
 * each other - at which point the images write successfully, Instagram fails to
 * fetch them, and the error is a generic "media could not be retrieved" from a
 * server on another continent. Writing a small file and asking for it over HTTP
 * turns that into an answer before anything is posted.</p>
 */
final class InstagramPublishCheck {

    private InstagramPublishCheck() { }

    /** @return null when the pairing works, or a description of what is wrong */
    static String run(InstagramAccount account) {
        final String marker = "weathermap-check-" + System.currentTimeMillis() + ".txt";
        final Path file = account.publishDir().resolve(marker);
        final String token = "weathermap " + System.nanoTime();

        try {
            Files.createDirectories(account.publishDir());
            Files.writeString(file, token, StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            return "The publish folder could not be written:\n" + account.publishDir()
                    + "\n\n" + e.getMessage();
        }

        try {
            final String fetched = Http.getString(URI.create(account.urlFor(marker)));
            if (!fetched.contains(token)) {
                return "That URL answered, but with something else.\n\n"
                        + account.urlFor(marker) + "\n\nThe folder and the URL are "
                        + "probably different places.";
            }
            return null;
        }
        catch (Exception e) {
            return "The folder was written, but Instagram will not be able to fetch it:\n\n"
                    + account.urlFor(marker) + "\n\n" + e.getMessage()
                    + "\n\nThe public URL must serve exactly the publish folder.";
        }
        finally {
            try {
                Files.deleteIfExists(file);
            }
            catch (Exception e) {
                // A stray check file is untidy, not a failure worth reporting.
            }
        }
    }
}
