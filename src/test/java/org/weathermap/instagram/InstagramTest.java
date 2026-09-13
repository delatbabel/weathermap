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
}
