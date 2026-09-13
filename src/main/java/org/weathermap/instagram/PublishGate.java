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
                if (isReachable(url)) break;
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
