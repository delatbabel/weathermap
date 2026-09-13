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
 *
 * <h2>One marker file, reused</h2>
 *
 * <p>The marker has a fixed name and is overwritten by each check, rather than
 * carrying a timestamp and being deleted afterwards. The delete is not reliable:
 * on an rclone mount over Cloudflare R2 it fails with {@code EIO} - rclone
 * carries the version ID that R2 returns on a write and sends it back on the
 * delete, and R2 does not implement versioning - so a per-run name left a
 * permanent file in a public bucket every time the button was pressed, and the
 * failure was swallowed as untidiness. A fixed name cannot accumulate.</p>
 *
 * <p>Being fixed, the URL could be answered from a cache, which would let a
 * check pass on a stale copy of itself. The body carries a fresh nonce that must
 * come back, and the request carries it as a query parameter as well, which is
 * part of the cache key.</p>
 */
final class InstagramPublishCheck {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(InstagramPublishCheck.class.getName());

    private InstagramPublishCheck() { }

    /** @return null when the pairing works, or a description of what is wrong */
    /** Fixed, so repeated checks overwrite one object instead of leaving many. */
    private static final String MARKER = "weathermap-check.txt";

    static String run(InstagramAccount account) {
        final Path file = account.publishDir().resolve(MARKER);
        final String nonce = Long.toUnsignedString(System.nanoTime(), 36)
                + Long.toUnsignedString(new java.util.Random().nextLong(), 36);
        final String token = "weathermap " + nonce;

        try {
            Files.createDirectories(account.publishDir());
            Files.writeString(file, token, StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            return "The publish folder could not be written:\n" + account.publishDir()
                    + "\n\n" + e.getMessage();
        }

        try {
            if (account.hasSyncCommand()) {
                org.weathermap.instagram.PublishGate.sync(account, m -> { });
            }
            // The nonce is in the query as well as the body: a fixed URL can be
            // cached, and a check that passes on a stale copy of itself proves
            // nothing.
            final String fetched = Http.getString(
                    URI.create(account.urlFor(MARKER) + "?t=" + nonce));
            if (!fetched.contains(token)) {
                return "That URL answered, but with something else.\n\n"
                        + account.urlFor(MARKER) + "\n\nThe folder and the URL are "
                        + "probably different places.";
            }
            return null;
        }
        catch (Exception e) {
            return "The folder was written, but Instagram will not be able to fetch it:\n\n"
                    + account.urlFor(MARKER) + "\n\n" + e.getMessage()
                    + "\n\nThe public URL must serve exactly the publish folder.";
        }
        finally {
            try {
                Files.deleteIfExists(file);
            }
            catch (Exception e) {
                // Expected on an R2 mount, and harmless now the name is fixed:
                // the next check overwrites this file rather than adding another.
                LOG.fine(() -> "Could not remove " + file + ": " + e);
            }
        }
    }
}
