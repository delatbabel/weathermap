package org.weathermap.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
