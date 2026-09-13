package org.weathermap.instagram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weathermap.MapService;
import org.weathermap.model.InstagramAccount;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstagramTest {

    private static MapService.Result chart(String validTime) {
        return new MapService.Result(
                new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB),
                null, Path.of("/tmp/x.png"), Instant.parse(validTime));
    }

    private static List<MapService.Result> series(int n) {
        final List<MapService.Result> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(chart(String.format("2026-09-13T%02d:00:00Z", i)));
        }
        return out;
    }

    // ---- which charts go in ------------------------------------------------

    @Test
    void thePostStartsAtTheChartOnScreenAndRunsForwards() {
        final List<MapService.Result> all = series(8);
        final List<MapService.Result> chosen = ChartPublisher.selectFrom(all, 2, 4);

        assertEquals(4, chosen.size());
        assertSame(all.get(2), chosen.get(0), "the first image is the chart on screen");
        assertSame(all.get(5), chosen.get(3));
    }

    /**
     * Near the end of a series there is simply less to post. Wrapping round
     * would put the oldest chart after the newest, which is not a forecast.
     */
    @Test
    void theEndOfASeriesGivesFewerChartsRatherThanWrapping() {
        final List<MapService.Result> all = series(5);
        final List<MapService.Result> chosen = ChartPublisher.selectFrom(all, 3, 4);

        assertEquals(2, chosen.size());
        assertSame(all.get(3), chosen.get(0));
        assertSame(all.get(4), chosen.get(1));
    }

    // ---- what is written ---------------------------------------------------

    @Test
    void chartsAreWrittenAsJpegAtTheConfiguredUrl(@TempDir Path dir) throws Exception {
        final InstagramAccount account = new InstagramAccount(
                InstagramAccount.Login.INSTAGRAM,
                "@saigonweather", "17841400000000000", "token",
                dir, "https://example.test/charts/", "", "");

        final List<InstagramClient.CarouselImage> images =
                new ChartPublisher(account).publish(series(3));

        assertEquals(3, images.size());
        for (InstagramClient.CarouselImage image : images) {
            assertTrue(image.url().startsWith("https://example.test/charts/"), image.url());
            assertTrue(image.url().endsWith(".jpg"), "carousels are JPEG only: " + image.url());
            assertTrue(image.altText().contains("Weather chart valid"), image.altText());
        }
        try (var files = Files.list(dir)) {
            assertEquals(3, files.filter(f -> f.toString().endsWith(".jpg")).count());
        }
    }

    /** JPEG has no alpha; without an explicit ground, transparency comes out black. */
    @Test
    void aJpegIsWrittenOnWhiteNotBlack(@TempDir Path dir) throws Exception {
        final BufferedImage transparent =
                new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        final Path file = dir.resolve("x.jpg");
        ChartPublisher.writeJpeg(transparent, file);

        final BufferedImage back = javax.imageio.ImageIO.read(file.toFile());
        final int corner = back.getRGB(0, 0) & 0xFFFFFF;
        assertTrue(corner > 0xF0F0F0, "expected white, got " + Integer.toHexString(corner));
    }

    // ---- the account -------------------------------------------------------

    @Test
    void anAccountIsOnlyCompleteWithEverythingTheApiNeeds(@TempDir Path dir) {
        assertFalse(new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "@x", "", "t", dir, "https://h", "", "").isComplete(),
                    "no account id");
        assertFalse(new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "@x", "1", "", dir, "https://h", "", "").isComplete(),
                    "no token");
        assertFalse(new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "@x", "1", "t", null, "https://h", "", "").isComplete(),
                    "no publish folder");
        assertFalse(new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "@x", "1", "t", dir, "", "", "").isComplete(),
                    "no public URL - Instagram fetches every image");
        assertTrue(new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "@x", "1", "t", dir, "https://h", "", "").isComplete());
    }

    @Test
    void theUrlJoinsCleanlyWhicheverWayTheBaseWasTyped(@TempDir Path dir) {
        assertEquals("https://h/c/chart.jpg",
                new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "", "1", "t", dir, "https://h/c", "", "").urlFor("chart.jpg"));
        assertEquals("https://h/c/chart.jpg",
                new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "", "1", "t", dir, "https://h/c///", "", "").urlFor("chart.jpg"));
    }

    /** A token in a log or a crash report is a token someone else can post with. */
    @Test
    void theTokenIsNeverPrinted(@TempDir Path dir) {
        final String secret = "EAAG-super-secret-token";
        final String shown = new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "@x", "1", secret, dir, "https://h", "", "").toString();

        assertFalse(shown.contains(secret), shown);
        assertTrue(shown.contains("token present"), shown);
    }

    @Test
    void anAccountSurvivesBeingStored(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("instagram.properties");
        final InstagramAccount saved = new InstagramAccount(
                InstagramAccount.Login.INSTAGRAM,
                "@saigonweather", "17841400000000000", "EAAG-token",
                dir.resolve("charts"), "https://example.test/charts",
                "rclone sync . r2:charts", "");
        saved.save(file);

        final InstagramAccount back = InstagramAccount.load(file).orElseThrow();
        assertEquals(saved, back);

        // A bearer credential should not be world-readable.
        final var perms = Files.getPosixFilePermissions(file);
        assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ), "" + perms);
        assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ), "" + perms);
    }

    @Test
    void noStoredAccountIsEmptyRatherThanAnError(@TempDir Path dir) {
        assertTrue(InstagramAccount.load(dir.resolve("absent.properties")).isEmpty());
    }

    // ---- the API's limits --------------------------------------------------

    @Test
    void aCarouselIsTwoToTenImages(@TempDir Path dir) {
        final var client = new InstagramClient(
                new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "", "1", "t", dir, "https://h", "", ""));
        final var one = List.of(new InstagramClient.CarouselImage("https://h/a.jpg", ""));
        final var eleven = new ArrayList<InstagramClient.CarouselImage>();
        for (int i = 0; i < 11; i++) {
            eleven.add(new InstagramClient.CarouselImage("https://h/" + i + ".jpg", ""));
        }

        assertThrows(IllegalArgumentException.class, () -> client.postCarousel(one, ""));
        assertThrows(IllegalArgumentException.class, () -> client.postCarousel(eleven, ""));
    }

    @Test
    void responsesAreReadForIdsAndErrors() {
        assertEquals("17895695668004550",
                InstagramClient.field("{\"id\":\"17895695668004550\"}", "id"));
        assertEquals("4", InstagramClient.field("{\"quota_usage\":4}", "quota_usage"));
        assertEquals("The image could not be fetched",
                InstagramClient.field(
                        "{\"error\":{\"message\":\"The image could not be fetched\"}}", "message"));
        assertNull(InstagramClient.field("{\"error\":{}}", "id"));
    }

    // ---- the two login paths ----------------------------------------------

    /**
     * A token from one path is refused by the other's host, and the failure
     * reads like a bad token rather than a wrong address - so the host has to
     * follow the token, not a constant.
     */
    @Test
    void theApiHostFollowsWhereTheTokenCameFrom() {
        assertEquals("https://graph.instagram.com/v25.0",
                     InstagramAccount.Login.INSTAGRAM.apiBase());
        assertEquals("https://graph.facebook.com/v21.0",
                     InstagramAccount.Login.FACEBOOK.apiBase());
    }

    @Test
    void thePathIsStoredAndComesBack(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("instagram.properties");
        new InstagramAccount(InstagramAccount.Login.FACEBOOK, "@x", "1", "t",
                             dir, "https://h", "", "").save(file);

        assertEquals(InstagramAccount.Login.FACEBOOK,
                     InstagramAccount.load(file).orElseThrow().login());
    }

    /** An older file, or a hand-edited one, opens on the simpler path. */
    @Test
    void anUnknownOrMissingPathDefaultsToInstagramLogin(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("instagram.properties");
        Files.writeString(file, "instagram.userId=1\ninstagram.accessToken=t\n"
                + "instagram.publishDir=" + dir + "\ninstagram.publicBaseUrl=https://h\n"
                + "instagram.login=something-else\n");

        assertEquals(InstagramAccount.Login.INSTAGRAM,
                     InstagramAccount.load(file).orElseThrow().login());
    }

    /** Each path needs its own scopes; naming the wrong set wastes a setup. */
    @Test
    void eachPathNamesItsOwnScopes() {
        assertTrue(InstagramAccount.Login.INSTAGRAM.scopes()
                .contains("instagram_business_content_publish"));
        assertTrue(InstagramAccount.Login.FACEBOOK.scopes()
                .contains("instagram_content_publish"));
    }

    /**
     * Writing the files and posting are one action to the user and two events
     * on the internet. A synced folder is not live the moment it is written, and
     * posting then makes Meta fetch a URL that does not exist yet.
     */
    @Test
    void aSyncedFolderIsRecognisedAsNeedingAStepBeforePosting(@TempDir Path dir) {
        assertFalse(new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "", "1", "t",
                dir, "https://h", "", "").hasSyncCommand(),
                "a directly served folder needs nothing");
        assertTrue(new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "", "1", "t",
                dir, "https://h", "rclone sync . r2:charts", "").hasSyncCommand());
    }

    @Test
    void theSyncCommandIsStoredWithTheRest(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("instagram.properties");
        new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "@x", "1", "t",
                dir, "https://h", "rclone sync . r2:charts", "").save(file);

        assertEquals("rclone sync . r2:charts",
                     InstagramAccount.load(file).orElseThrow().syncCommand());
    }

    // ---- the caption is worth keeping --------------------------------------

    /**
     * A daily chart's caption is yesterday's with the date and a line changed.
     * Losing it every time meant retyping the whole thing to change a word.
     */
    @Test
    void theCaptionComesBackWithTheAccount(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("instagram.properties");
        final String text = "Morning charts for 13 Sep\n\n#saigonweather #gfs";
        new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "@x", "1", "t",
                dir, "https://h", "", text).save(file);

        assertEquals(text, InstagramAccount.load(file).orElseThrow().caption());
    }

    @Test
    void aCaptionKeepsItsOwnBlankLines(@TempDir Path dir) {
        final String text = "\n  indented, and a leading blank line\n";
        assertEquals(text, new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "", "1", "t",
                dir, "https://h", "", text).caption());
    }

    @Test
    void anAccountStoredBeforeCaptionsOpensWithAnEmptyOne(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("instagram.properties");
        Files.writeString(file, "instagram.userId=1\ninstagram.accessToken=t\n"
                + "instagram.publishDir=" + dir + "\ninstagram.publicBaseUrl=https://h\n");

        assertEquals("", InstagramAccount.load(file).orElseThrow().caption());
    }

    // ---- what Meta actually said -------------------------------------------

    /**
     * Meta rejects a publish with HTTP 400 and a JSON body naming the cause.
     * The status alone says only that something was wrong, so every distinct
     * setup mistake - a wrong host, an unfetchable URL, an expired token -
     * arrived as the same unactionable line.
     */
    @Test
    void metasOwnExplanationReachesTheUser() {
        final String body = "{\"error\":{\"message\":\"The image could not be fetched\","
                + "\"type\":\"OAuthException\",\"code\":9004}}";
        final var failure = new org.weathermap.util.Http.HttpStatusException(
                400, java.net.URI.create("https://graph.instagram.com/v25.0/1/media"), body);

        assertTrue(failure.getMessage().contains("The image could not be fetched"),
                   failure.getMessage());
        assertEquals(body, failure.body());
        assertTrue(failure.isPermanent(), "a 400 cannot be retried into a 200");
    }

    @Test
    void aServerErrorIsStillWorthRetrying() {
        assertFalse(new org.weathermap.util.Http.HttpStatusException(
                503, java.net.URI.create("https://h/x"), "").isPermanent());
    }

    /**
     * The image file must be closed, not merely written.
     *
     * <p>Closing an {@code ImageOutputStream} flushes its cache into the stream
     * beneath it and leaves that stream open, so a stream created inline in the
     * {@code createImageOutputStream(...)} call is never closed by anything. On
     * an ordinary filesystem that is invisible - the bytes are all there and the
     * file reads back perfectly, which is why it survived the tests above.</p>
     *
     * <p>On the rclone mount these charts are published through it decides the
     * feature. With {@code --vfs-cache-mode writes} the upload is triggered by
     * {@code close()}: an unclosed file is written, listed and readable locally
     * and never reaches the bucket. Instagram fetches every image from a public
     * URL, so the post failed on a URL that started working the moment the
     * application was shut down and the kernel reaped the descriptors.</p>
     *
     * <p>Checked through {@code /proc/self/fd}, which is the only way to see a
     * descriptor that is open but harmless locally.</p>
     */
    @Test
    void theImageFileIsClosedAndNotJustWritten(@TempDir Path dir) throws Exception {
        final Path proc = Path.of("/proc/self/fd");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(proc), "needs /proc to see open descriptors");

        final Path file = dir.resolve("closed.jpg");
        ChartPublisher.writeJpeg(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), file);

        final String target = file.toRealPath().toString();
        final List<String> stillOpen = new ArrayList<>();
        try (var fds = Files.list(proc)) {
            for (Path fd : fds.toList()) {
                try {
                    if (Files.readSymbolicLink(fd).toString().equals(target)) {
                        stillOpen.add(fd.getFileName().toString());
                    }
                }
                catch (java.io.IOException ignored) {
                    // The descriptor closed while we were looking, which is fine.
                }
            }
        }
        assertTrue(stillOpen.isEmpty(),
                "the chart is still open on fd " + stillOpen + " - on an rclone mount "
                + "that means it is never uploaded: " + target);
    }

    // ---- the container is not ready when its id comes back -----------------

    /**
     * Meta answers the container call with an ID and then goes off to fetch the
     * image. Publishing before it has finished is refused with "Media ID is not
     * available", in the account's own language - which for a Vietnamese account
     * arrives as text no English-reading log reader can act on, attached to a
     * step that looked like it had already succeeded.
     */
    @Test
    void aContainerIsWaitedForRatherThanPublishedImmediately() throws Exception {
        final var states = new java.util.ArrayDeque<>(List.of(
                "{\"status_code\":\"IN_PROGRESS\"}",
                "{\"status_code\":\"FINISHED\"}"));
        final var polls = new java.util.concurrent.atomic.AtomicInteger();

        final var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final String path = exchange.getRequestURI().getPath();
            final String body;
            if ("GET".equals(exchange.getRequestMethod())) {
                polls.incrementAndGet();
                body = states.size() > 1 ? states.poll() : states.peek();
            }
            else if (path.endsWith("/media_publish")) {
                body = "{\"id\":\"published-1\"}";
            }
            else {
                body = "{\"id\":\"container-" + polls.get() + "\"}";
            }
            final byte[] out = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (var os = exchange.getResponseBody()) { os.write(out); }
        });
        server.start();
        try {
            final String base = "http://127.0.0.1:" + server.getAddress().getPort();
            final var client = new InstagramClient(
                    new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "", "1", "t",
                            Path.of("/tmp"), "https://h", "", ""), base);

            final String id = client.postCarousel(List.of(
                    new InstagramClient.CarouselImage("https://h/a.jpg", ""),
                    new InstagramClient.CarouselImage("https://h/b.jpg", "")), "hello");

            assertEquals("published-1", id);
            assertTrue(polls.get() >= 3,
                       "each image container and the carousel are checked: " + polls.get());
        }
        finally {
            server.stop(0);
        }
    }

    /** An unfetchable image is final, and saying so beats waiting out the timeout. */
    @Test
    void aRejectedContainerFailsAtOnceInsteadOfWaiting() throws Exception {
        final var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final String body = "GET".equals(exchange.getRequestMethod())
                    ? "{\"status_code\":\"ERROR\",\"status\":\"Media could not be fetched\"}"
                    : "{\"id\":\"c1\"}";
            final byte[] out = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (var os = exchange.getResponseBody()) { os.write(out); }
        });
        server.start();
        try {
            final var client = new InstagramClient(
                    new InstagramAccount(InstagramAccount.Login.INSTAGRAM, "", "1", "t",
                            Path.of("/tmp"), "https://h", "", ""),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            final var thrown = assertThrows(java.io.IOException.class, () ->
                    client.postCarousel(List.of(
                            new InstagramClient.CarouselImage("https://h/a.jpg", ""),
                            new InstagramClient.CarouselImage("https://h/b.jpg", "")), ""));

            assertTrue(thrown.getMessage().contains("Media could not be fetched"),
                       thrown.getMessage());
        }
        finally {
            server.stop(0);
        }
    }

    /**
     * Meta files many unrelated failures under OAuthException, so the type alone
     * does not mean the token is wrong. Saying so over "Media ID is not
     * available" sent someone to check credentials that were working.
     */
    @Test
    void onlyATokenErrorBlamesTheToken() throws Exception {
        final var notReady = InstagramClient.explain(
                "{\"error\":{\"message\":\"Media ID is not available\","
                + "\"type\":\"OAuthException\"}}", "publish");
        assertFalse(notReady.contains("Token from"), notReady);
        assertTrue(notReady.contains("Media ID is not available"), notReady);

        final var badToken = InstagramClient.explain(
                "{\"error\":{\"message\":\"Invalid OAuth access token\","
                + "\"type\":\"OAuthException\"}}", "publish");
        assertTrue(badToken.contains("Token from"), badToken);
    }

    /** The account's own language carries the detail; both halves are shown. */
    @Test
    void aLocalisedExplanationIsNotDiscarded() {
        final var said = InstagramClient.explain(
                "{\"error\":{\"message\":\"Media ID is not available\","
                + "\"error_user_msg\":\"Phuong tien nay chua san sang dang\"}}", "publish");
        assertTrue(said.contains("Media ID is not available"), said);
        assertTrue(said.contains("Phuong tien nay chua san sang dang"), said);
    }
}
