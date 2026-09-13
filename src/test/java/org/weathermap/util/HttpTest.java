package org.weathermap.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTest {

    /**
     * The User-Agent is load-bearing, and a placeholder in it is worse than a
     * plain string.
     *
     * <p>Two of the Overpass instances refused every request with HTTP 429 and
     * the body "Please include a meaningful User-Agent string" purely because
     * ours carried {@code example.invalid}. A 429 reads as rate limiting, so it
     * was waited out instead of read.</p>
     */
    @Test
    void theUserAgentIdentifiesTheApplicationAndPromisesNothingFalse() {
        final String agent = Http.USER_AGENT.toLowerCase(Locale.ROOT);

        assertTrue(agent.startsWith("weathermap/"), Http.USER_AGENT);
        for (String placeholder : new String[]{"example.", "localhost", "invalid",
                                               "todo", "changeme", "your-"}) {
            assertFalse(agent.contains(placeholder),
                        "a placeholder in the User-Agent gets the request refused: "
                                + Http.USER_AGENT);
        }
    }

    /** The server's own words, not our guess about what it meant. */
    @Test
    void aRefusalQuotesWhatTheServerSaid() {
        final String message = new Http.RateLimitedException(
                "overpass.kumi.systems",
                "Please include a meaningful User-Agent string with your requests "
                        + "to avoid rate-limiting.").getMessage();

        assertTrue(message.contains("overpass.kumi.systems"), message);
        assertTrue(message.contains("meaningful User-Agent"), message);
        // The old guess sent someone away to wait for a condition that never passes.
        assertFalse(message.contains("wait a few minutes"), message);
    }

    @Test
    void aRefusalWithNoBodyStillSaysSomethingUseful() {
        final String message =
                new Http.RateLimitedException("overpass-api.de", "  ").getMessage();

        assertTrue(message.contains("overpass-api.de"), message);
        assertTrue(message.contains("429"), message);
    }

    /** A long server message must not fill a status bar. */
    @Test
    void aVerboseRefusalIsTrimmed() {
        final String message =
                new Http.RateLimitedException("host", "x".repeat(2000)).getMessage();
        assertTrue(message.length() < 400, "length " + message.length());
    }

    // ---- how many times a rejection is repeated ----------------------------

    /** Counts requests, and answers each with a fixed status and body. */
    private static com.sun.net.httpserver.HttpServer serverReturning(
            int status, String body, java.util.concurrent.atomic.AtomicInteger hits)
            throws java.io.IOException {
        final var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            final byte[] out = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (var os = exchange.getResponseBody()) { os.write(out); }
        });
        server.start();
        return server;
    }

    /**
     * A 4xx is the server saying the request itself is wrong, so sending it
     * again is asking an identical question after an identical answer.
     *
     * <p>This is regression cover for a loop that claimed exactly that and did
     * the opposite: the throw for a 4xx stood inside the {@code try} whose
     * {@code catch} handled retryable failures, so it was caught, filed as the
     * attempt's error and repeated. Three requests went out for every
     * rejection, and the log read "retrying" over a condition that could not
     * change - which is what a failing Instagram post looked like.</p>
     */
    @Test
    void aRejectedRequestIsSentOnceAndNotThreeTimes() throws Exception {
        final var hits = new java.util.concurrent.atomic.AtomicInteger();
        final var server = serverReturning(
                400, "{\"error\":{\"message\":\"Invalid parameter\"}}", hits);
        try {
            final java.net.URI uri = java.net.URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/media");

            final var thrown = assertThrows(Http.HttpStatusException.class,
                    () -> Http.postForm(uri, "x=1"));

            assertEquals(1, hits.get(), "a 400 must not be retried");
            assertEquals(400, thrown.status());
            assertTrue(thrown.getMessage().contains("Invalid parameter"),
                       "the server's explanation is the useful part: " + thrown.getMessage());
        }
        finally {
            server.stop(0);
        }
    }

    /** A 5xx may well be transient, so it keeps its retries. */
    @Test
    void aServerFailureIsStillRetried() throws Exception {
        final var hits = new java.util.concurrent.atomic.AtomicInteger();
        final var server = serverReturning(503, "busy", hits);
        try {
            final java.net.URI uri = java.net.URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/x");

            assertThrows(java.io.IOException.class, () -> Http.postForm(uri, "x=1"));
            assertEquals(3, hits.get(), "5xx keeps its retries");
        }
        finally {
            server.stop(0);
        }
    }

    /** Overpass answers 429 for a User-Agent it dislikes; repeating it cannot help. */
    @Test
    void aRateLimitIsNotRetriedEither() throws Exception {
        final var hits = new java.util.concurrent.atomic.AtomicInteger();
        final var server = serverReturning(429, "slow down", hits);
        try {
            final java.net.URI uri = java.net.URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/x");

            assertThrows(Http.RateLimitedException.class, () -> Http.postForm(uri, "x=1"));
            assertEquals(1, hits.get());
        }
        finally {
            server.stop(0);
        }
    }
}
