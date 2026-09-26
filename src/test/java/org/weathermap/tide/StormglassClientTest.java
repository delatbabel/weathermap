package org.weathermap.tide;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.weathermap.util.Cache;
import org.weathermap.util.Http;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StormglassClientTest {

    /** The sea level response, in the shape the API documentation gives it. */
    private static final String SEA_LEVEL = """
            {
                "data": [
                    {"sg": -0.62, "time": "2026-09-26T00:00:00+00:00"},
                    {"sg": 0.16,  "time": "2026-09-26T01:00:00+00:00"},
                    {"sg": 0.90,  "time": "2026-09-26T02:00:00+00:00"}
                ],
                "meta": {
                    "cost": 1, "dailyQuota": 10, "requestCount": 4,
                    "datum": "MSL", "lat": 43.38, "lng": -3.01,
                    "station": {"distance": 4, "lat": 43.36, "lng": -3.05,
                                "name": "bilbao", "source": "sg"}
                }
            }
            """;

    /**
     * The extremes response. Note the timestamps: the documentation writes
     * these with a space where the sea level endpoint writes a T.
     */
    private static final String EXTREMES = """
            {
                "data": [
                    {"height": 1.18, "time": "2026-09-26 03:40:44+00:00", "type": "high"},
                    {"height": 0.60, "time": "2026-09-26 09:53:54+00:00", "type": "low"}
                ],
                "meta": {
                    "cost": 1, "dailyQuota": 10, "requestCount": 5,
                    "station": {"distance": 4, "lat": 43.36, "lng": -3.05,
                                "name": "bilbao", "source": "sg"}
                }
            }
            """;

    /** Answers either endpoint, and records what was asked and with what key. */
    private static final class Service implements AutoCloseable {
        final HttpServer server;
        final List<String> asked = Collections.synchronizedList(new ArrayList<>());
        final List<String> keys = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger status = new AtomicInteger(200);

        Service() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                asked.add(exchange.getRequestURI().toString());
                keys.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                final String body = status.get() != 200
                        ? "{\"errors\":{\"key\":\"refused\"}}"
                        : exchange.getRequestURI().getPath().contains("extremes")
                                ? EXTREMES : SEA_LEVEL;
                final byte[] out = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), out.length);
                try (var os = exchange.getResponseBody()) { os.write(out); }
            });
            server.start();
        }

        String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v2/"; }

        @Override
        public void close() { server.stop(0); }
    }

    private static Cache freshCache() throws Exception {
        return new Cache(Files.createTempDirectory("tide-cache"));
    }

    // ---- the request -------------------------------------------------------

    @Test
    void asksBothEndpointsWithTheKeyBareInTheHeader() throws Exception {
        try (Service service = new Service()) {
            final StormglassClient client =
                    new StormglassClient(service.base(), freshCache(), () -> "a-key");
            client.fetch(43.38, -3.01, LocalDate.of(2026, 9, 26), 14);

            assertEquals(2, service.asked.size());
            assertTrue(service.asked.get(0).contains("/v2/tide/sea-level/point"), service.asked.get(0));
            assertTrue(service.asked.get(1).contains("/v2/tide/extremes/point"), service.asked.get(1));

            // Bare, not "Bearer a-key". Storm Glass answers the prefixed form
            // with a 403 that reads as a bad key rather than a bad header.
            assertEquals(List.of("a-key", "a-key"), service.keys);
        }
    }

    @Test
    void asksForTheRequestedWindowAsUnixSeconds() {
        final StormglassClient client =
                new StormglassClient("https://example.test/v2/", new Cache(Path.of("/tmp/x")),
                                     () -> "k");
        final URI uri = client.uriFor("tide/sea-level/point", 10.5, -3.25,
                                      1_700_000_000L, 1_701_209_600L);
        assertEquals("https://example.test/v2/tide/sea-level/point"
                + "?lat=10.5&lng=-3.25&start=1700000000&end=1701209600", uri.toString());
    }

    /**
     * The coordinates are rounded before they reach the URL, so that looking at
     * the same harbour twice is one cache entry and not two requests.
     */
    @Test
    void roundsTheCoordinatesSoTheCacheKeyStopsMoving() throws Exception {
        try (Service service = new Service()) {
            final Cache cache = freshCache();
            final StormglassClient client =
                    new StormglassClient(service.base(), cache, () -> "k");

            client.fetch(43.3801234, -3.0109876, LocalDate.of(2026, 9, 26), 14);
            client.fetch(43.3799111, -3.0110432, LocalDate.of(2026, 9, 26), 14);

            assertEquals(2, service.asked.size(), "the second look is the same hundred metres");
            assertTrue(service.asked.get(0).contains("lat=43.38"), service.asked.get(0));
        }
    }

    // ---- the answer --------------------------------------------------------

    @Test
    void readsBothHalvesIntoOnePrediction() throws Exception {
        try (Service service = new Service()) {
            final StormglassClient client =
                    new StormglassClient(service.base(), freshCache(), () -> "k");
            final TideData data = client.fetch(43.38, -3.01, LocalDate.of(2026, 9, 26), 14);

            assertEquals(3, data.seaLevel().size());
            assertEquals(-0.62, data.seaLevel().get(0).metres(), 1e-9);
            assertEquals(Instant.parse("2026-09-26T00:00:00Z"), data.seaLevel().get(0).time());

            assertEquals(2, data.extremes().size());
            assertTrue(data.extremes().get(0).isHigh());
            assertEquals(1.18, data.extremes().get(0).metres(), 1e-9);
            // The space-separated timestamp, read rather than dropped.
            assertEquals(Instant.parse("2026-09-26T03:40:44Z"), data.extremes().get(0).time());

            assertEquals("MSL", data.datum());
            assertEquals("bilbao", data.station().name());
            assertEquals(4.0, data.station().distanceKm(), 1e-9);
            assertFalse(data.cached());
            assertEquals(10, data.quota().dailyQuota());
        }
    }

    @Test
    void groupsTheDayByTheZoneItIsAskedAbout() throws Exception {
        try (Service service = new Service()) {
            final StormglassClient client =
                    new StormglassClient(service.base(), freshCache(), () -> "k");
            final TideData data = client.fetch(43.38, -3.01, LocalDate.of(2026, 9, 26), 14);

            // Both extremes fall on the 26th in Madrid, two hours ahead. Seven
            // hours behind, in Los Angeles, the first of them is the evening of
            // the 25th and only the second is the 26th - which is the whole
            // reason the window asks for a day either side of the fortnight
            // rather than exactly the days it means to draw.
            assertEquals(2, data.extremesFor(LocalDate.of(2026, 9, 26),
                                             ZoneId.of("Europe/Madrid")).size());
            assertEquals(1, data.extremesFor(LocalDate.of(2026, 9, 25),
                                             ZoneId.of("America/Los_Angeles")).size());
            assertEquals(1, data.extremesFor(LocalDate.of(2026, 9, 26),
                                             ZoneId.of("America/Los_Angeles")).size());
        }
    }

    // ---- the daily limit ---------------------------------------------------

    /**
     * The point of the whole design: a second look at the same place on the
     * same day must not cost anything, because the free tier allows ten
     * requests and one chart costs two.
     */
    @Test
    void asksOnceADayAndServesTheRestFromTheCache() throws Exception {
        try (Service service = new Service()) {
            final Cache cache = freshCache();
            final StormglassClient first =
                    new StormglassClient(service.base(), cache, () -> "k");
            first.fetch(43.38, -3.01, LocalDate.of(2026, 9, 26), 14);
            assertEquals(2, service.asked.size());

            // A new client, as if the application had been restarted.
            final StormglassClient again =
                    new StormglassClient(service.base(), cache, () -> "k");
            final TideData cached = again.fetch(43.38, -3.01, LocalDate.of(2026, 9, 26), 14);

            assertEquals(2, service.asked.size(), "no further request");
            assertTrue(cached.cached());
            assertEquals(3, cached.seaLevel().size(), "and the same answer");
        }
    }

    @Test
    void aStaleEntryIsFetchedAgain() throws Exception {
        try (Service service = new Service()) {
            final Cache cache = freshCache();
            final StormglassClient client =
                    new StormglassClient(service.base(), cache, () -> "k");
            client.fetch(43.38, -3.01, LocalDate.of(2026, 9, 26), 14);

            // Age every entry past the day it is kept for.
            try (var entries = Files.walk(cache.root())) {
                for (Path entry : entries.filter(Files::isRegularFile).toList()) {
                    Files.setLastModifiedTime(entry, java.nio.file.attribute.FileTime.from(
                            Instant.now().minus(StormglassClient.TTL).minusSeconds(60)));
                }
            }

            client.fetch(43.38, -3.01, LocalDate.of(2026, 9, 26), 14);
            assertEquals(4, service.asked.size());
        }
    }

    // ---- when it will not work ---------------------------------------------

    @Test
    void refusesToAskWithoutAKey() throws Exception {
        try (Service service = new Service()) {
            final StormglassClient client =
                    new StormglassClient(service.base(), freshCache(), () -> "  ");
            assertFalse(client.hasApiKey());

            final java.io.IOException e = assertThrows(java.io.IOException.class,
                    () -> client.fetch(43.38, -3.01, LocalDate.of(2026, 9, 26), 14));
            assertTrue(e.getMessage().contains("Preferences"), e.getMessage());
            assertEquals(0, service.asked.size(), "and does not spend a request finding out");
        }
    }

    /**
     * 402 is the daily limit and 403 is the key. Neither status says so in a
     * way anyone would guess - "Payment Required" in particular sends people
     * to a billing page when all they have done is look at eleven harbours.
     */
    @Test
    void translatesTheTwoStatusCodesThatMeanSomethingElse() {
        assertTrue(StormglassClient.explain(
                        new Http.HttpStatusException(402, URI.create("https://x.test/"), ""))
                .getMessage().contains("daily request limit"));
        assertTrue(StormglassClient.explain(
                        new Http.HttpStatusException(403, URI.create("https://x.test/"), ""))
                .getMessage().contains("API key"));
        // Anything else is passed through with the server's own words.
        assertEquals(Http.HttpStatusException.class, StormglassClient.explain(
                new Http.HttpStatusException(503, URI.create("https://x.test/"), "")).getClass());
    }

    @Test
    void reportsALimitRefusalRatherThanTheStatusCode() throws Exception {
        try (Service service = new Service()) {
            service.status.set(402);
            final StormglassClient client =
                    new StormglassClient(service.base(), freshCache(), () -> "k");
            final java.io.IOException e = assertThrows(java.io.IOException.class,
                    () -> client.fetch(43.38, -3.01, LocalDate.of(2026, 9, 26), 14));
            assertTrue(e.getMessage().contains("daily request limit"), e.getMessage());
        }
    }

    // ---- timestamps ---------------------------------------------------------

    @Test
    void readsBothTimestampFormatsAndShrugsOffAThird() {
        assertEquals(Instant.parse("2020-02-24T00:00:00Z"),
                     StormglassClient.parseTime("2020-02-24T00:00:00+00:00"));
        assertEquals(Instant.parse("2019-03-15T03:40:44Z"),
                     StormglassClient.parseTime("2019-03-15 03:40:44+00:00"));
        assertEquals(Instant.parse("2019-03-15T02:40:44Z"),
                     StormglassClient.parseTime("2019-03-15 03:40:44+01:00"));
        // One unreadable point costs its own point, not the whole response.
        assertNull(StormglassClient.parseTime("the fifteenth"));
        assertNull(StormglassClient.parseTime(null));
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }

    @Test
    void theDescriptionNamesTheService() {
        final StormglassClient client = new StormglassClient(
                StormglassClient.DEFAULT_BASE, new Cache(Path.of("/tmp/x")), () -> "k");
        assertNotNull(client.description());
        assertTrue(client.description().contains("stormglass.io"), client.description());
    }
}
