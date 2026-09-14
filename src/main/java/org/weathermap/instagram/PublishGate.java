package org.weathermap.instagram;

import org.weathermap.model.InstagramAccount;
import org.weathermap.util.Http;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Makes sure the images are actually fetchable before Instagram is asked to
 * fetch them.
 *
 * <h2>The ordering problem</h2>
 *
 * <p>Writing the files and posting are one action to the user, but they are two
 * events on the internet. Where the publish folder <em>is</em> the web root -
 * a server on this machine, or a bucket mounted into the filesystem - the file
 * is live the moment it is written and there is nothing to wait for. Where the
 * folder is <em>synced</em> - Dropbox, rclone to object storage, anything with
 * an upload step - it is not, and posting immediately means Meta fetches a URL
 * that does not exist yet.</p>
 *
 * <p>That failure is not obviously about timing. The API reports that the media
 * could not be retrieved, which reads as a wrong URL, and by the time anyone
 * checks by hand the sync has finished and the URL works perfectly. So the gate
 * is explicit: run whatever upload the user configured, then poll until the
 * first and last images really answer, then post.</p>
 */
public final class PublishGate {

    private static final Logger LOG = Logger.getLogger(PublishGate.class.getName());

    /** Long enough for an ordinary sync, short enough not to look hung. */
    private static final Duration WAIT = Duration.ofMinutes(3);

    private static final Duration POLL = Duration.ofSeconds(2);

    /**
     * Makes each readiness request its own thing to the cache in front of the
     * bucket, and never asks for the plain address.
     *
     * <p>Cloudflare caches a 404 for an image, and for four hours: an R2 custom
     * domain answers a missing {@code .jpg} with {@code cache-control:
     * max-age=14400}. The gate used to poll the plain URL the moment the file
     * was written, which is a second or so before the mount has uploaded it -
     * so it asked for something that was not there yet, the miss was cached,
     * and every poll afterwards was answered from that cache with the same 404
     * while the object sat in the bucket. The wait then ran out against a file
     * that had been there almost the whole time.</p>
     *
     * <p>Worse, the poisoned entry was the one Instagram would have been sent
     * to. Polling a URL that nobody else will ever ask for leaves the plain one
     * untouched until the image really exists, so the first request for it -
     * Meta's - is a miss that reaches the bucket and is cached as a 200.</p>
     *
     * <p><b>A fresh value on every request, not one per run.</b> Polling one
     * probe URL repeatedly recreates the bug one step along: the first poll is
     * still made before the upload finishes, the 404 is still cached - under
     * the probe's address this time - and every poll after it is answered from
     * that cache for the rest of the wait. It was tried that way and failed
     * identically, three minutes of cached 404 for a file that arrived in
     * two seconds.</p>
     */
    static String probe(String url) {
        final String nonce = Long.toUnsignedString(System.nanoTime(), 36);
        return url + (url.indexOf('?') < 0 ? "?" : "&") + "ready=" + nonce;
    }

    private PublishGate() { }

    /**
     * Runs the account's sync command, if it has one.
     *
     * @throws IOException if the command fails, because posting after a failed
     *                     upload only produces a worse error later
     */
    public static void sync(InstagramAccount account, Consumer<String> progress)
            throws IOException, InterruptedException {

        if (!account.hasSyncCommand()) return;

        progress.accept("Running the sync command");
        LOG.info(() -> "sync: " + account.syncCommand());

        final ProcessBuilder builder = new ProcessBuilder("sh", "-c", account.syncCommand())
                .directory(account.publishDir().toFile())
                .redirectErrorStream(true);
        final Process process = builder.start();
        final String output = new String(process.getInputStream().readAllBytes());
        final int status = process.waitFor();

        if (status != 0) {
            throw new IOException("the sync command failed (exit " + status + "):\n"
                    + output.strip());
        }
        LOG.fine(() -> "sync finished: " + output.strip());
    }

    /**
     * Waits until the images can be fetched over HTTP.
     *
     * <p>Checks every image rather than only the first: a partial sync is a real
     * outcome, and a carousel that fails on its fourth item has still written
     * three containers and burned part of the day's quota.</p>
     *
     * @throws IOException if any image is still unreachable when the wait runs out
     */
    public static void awaitReachable(List<InstagramClient.CarouselImage> images,
                                      Consumer<String> progress)
            throws IOException, InterruptedException {

        final Instant deadline = Instant.now().plus(WAIT);
        for (int i = 0; i < images.size(); i++) {
            final String url = images.get(i).url();
            progress.accept("Checking image " + (i + 1) + " of " + images.size()
                    + " is reachable");

            while (true) {
                if (isReachable(probe(url))) break;
                if (Instant.now().isAfter(deadline)) {
                    throw new IOException(
                            "this image is still not reachable after "
                            + WAIT.toMinutes() + " minutes:\n\n" + url
                            + "\n\nInstagram fetches every image itself, so it cannot be "
                            + "posted until that URL answers. Check the public URL, and "
                            + "the sync command if the folder is uploaded rather than "
                            + "served directly.");
                }
                Thread.sleep(POLL.toMillis());
            }
        }
    }

    private static boolean isReachable(String url) throws InterruptedException {
        try {
            Http.getString(URI.create(url));
            return true;
        }
        catch (IOException e) {
            LOG.fine(() -> "not yet: " + url + " (" + e.getMessage() + ")");
            return false;
        }
    }
}
