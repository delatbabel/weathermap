package org.weathermap.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * The one place HTTP happens: a shared {@link HttpClient}, a User-Agent that
 * identifies the application, retries, and downloads that land atomically.
 *
 * <p><b>The User-Agent is not decoration, and a placeholder is worse than a
 * plain one.</b> Overpass instances police it: two of the three in use rejected
 * every request with {@code 429} and the body "Please include a meaningful
 * User-Agent string with your requests to avoid rate-limiting", because the
 * string carried {@code example.invalid}. A rejection that arrives as a 429
 * reads as rate limiting, so it was diagnosed as load and waited out, which of
 * course never helped - the same request would have failed a week later.</p>
 *
 * <p>So the default names the application and what it does and claims nothing
 * that is not true. Anyone running it hard enough to need a contact address can
 * set {@code -Dweathermap.userAgent=...}, which is what a courteous heavy user
 * should do; inventing a URL on their behalf is what caused this.</p>
 */
public final class Http {

    private static final Logger LOG = Logger.getLogger(Http.class.getName());

    /**
     * How this application identifies itself.
     *
     * <p>Override with {@code -Dweathermap.userAgent=...} to add a contact
     * address. Verified against all four Overpass instances in
     * {@code OverpassClient.DEFAULT_ENDPOINTS}: this string is accepted by every
     * one of them, and the same request with {@code example.invalid} in it is
     * refused by two.</p>
     */
    public static final String USER_AGENT = System.getProperty("weathermap.userAgent",
            "weathermap/0.1 (desktop GRIB charting; OSM data via Overpass)");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 3;

    /** Connect timeout when there is somewhere else to try. */
    private static final Duration FAILOVER_CONNECT_TIMEOUT = Duration.ofSeconds(8);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * A second client that gives up on a connection quickly.
     *
     * <p>For callers with a list of endpoints to work through. Java's
     * {@code HttpClient} resolves a host to one address and <b>does not fall
     * back to the others</b> - unlike curl, which tries them in turn. That is
     * not a hypothetical: {@code overpass-api.de} publishes two A records and
     * one of them was found black-holing connections from this machine, so the
     * client failed roughly half the time with what looked exactly like a
     * network outage. Waiting {@code CONNECT_TIMEOUT} for each of three retries
     * before trying anywhere else turned that into a minute of nothing
     * happening.</p>
     */
    private static final HttpClient FAILOVER_CLIENT = HttpClient.newBuilder()
            .connectTimeout(FAILOVER_CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() { }

    /**
     * The server is refusing because we have asked for too much, too often.
     *
     * <p>Its own type because the remedy is different from every other failure:
     * not "try again" but "ask for less". Java's {@code HttpClient} can also
     * surface a connection a rate-limiting server drops as a
     * {@code HttpConnectTimeoutException}, which reads as a network fault and
     * sends you looking in the wrong place entirely - that misdiagnosis is what
     * this type exists to prevent.</p>
     */
    public static final class RateLimitedException extends IOException {
        private static final long serialVersionUID = 1L;

        public RateLimitedException(String host) {
            this(host, null);
        }

        /**
         * @param explanation the server's own response body, if it sent one
         *
         * <p>Quoted rather than summarised. A 429 is not always about load:
         * Overpass returns one for a User-Agent it does not like, and the body
         * says so plainly while the status code does not. Guessing "ask for a
         * smaller area, or wait a few minutes" over the top of that sent someone
         * off to wait for a condition that was never going to pass.</p>
         */
        public RateLimitedException(String host, String explanation) {
            super(message(host, explanation));
        }

        private static String message(String host, String explanation) {
            final String said = explanation == null ? "" : explanation.strip();
            if (said.isEmpty()) {
                return "refused by " + host + " with HTTP 429 and no explanation - "
                        + "ask for a smaller area, or wait a few minutes";
            }
            // Kept short: this reaches a status bar as well as a log.
            final String trimmed = said.length() > 300 ? said.substring(0, 300) + "…" : said;
            return host + " refused the request (HTTP 429): " + trimmed;
        }
    }

    /**
     * A non-2xx status, with whatever the server said about it.
     *
     * <p><b>The body is the whole point.</b> Meta answers a bad publish with
     * HTTP 400 and a JSON body naming the cause - "The image could not be
     * fetched", "Invalid OAuth access token" - while the status alone says only
     * that something was wrong with the request. Throwing away the body turned
     * every distinct setup mistake into the same unactionable line, and
     * {@code InstagramClient} was written to read exactly those messages: it
     * never saw one, because the failure was raised here first.</p>
     */
    public static final class HttpStatusException extends IOException {
        private static final long serialVersionUID = 1L;

        private final int status;
        private final String body;

        public HttpStatusException(int status, URI uri, String body) {
            super(message(status, uri, body));
            this.status = status;
            this.body = body == null ? "" : body;
        }

        /** The HTTP status code. */
        public int status() { return status; }

        /** The response body, possibly empty - never null. */
        public String body() { return body; }

        /** True when repeating the identical request cannot help. */
        public boolean isPermanent() { return status >= 400 && status < 500; }

        private static String message(int status, URI uri, String body) {
            final String said = body == null ? "" : body.strip();
            if (said.isEmpty()) return "HTTP " + status + " from " + uri;
            final String trimmed = said.length() > 400 ? said.substring(0, 400) + "…" : said;
            return "HTTP " + status + " from " + uri + ": " + trimmed;
        }
    }

    /** Reports download progress; {@code total} is -1 when the server sends no length. */
    public interface ProgressListener {
        void onProgress(long bytesSoFar, long total);

        /** True to abandon the transfer at the next checkpoint. */
        default boolean isCancelled() { return false; }
    }

    /** GETs a URL as text. */
    public static String getString(URI uri) throws IOException, InterruptedException {
        return send(uri, HttpResponse.BodyHandlers.ofString()).body();
    }

    /**
     * GETs a URL as text, authenticated with a bearer token.
     *
     * <p>The token goes in a header rather than the query string, which is the
     * only difference that matters: a URL is written to the server's access log
     * and a header is not, and an access token in a log is a credential someone
     * else can post with.</p>
     */
    public static String getString(URI uri, String bearerToken)
            throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer " + bearerToken)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return sendWithRetry(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    /** POSTs a form body and reads the response as text - how Overpass is queried. */
    public static String postForm(URI uri, String body) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return sendWithRetry(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    /**
     * POSTs a form body with no retries and a short connect timeout, for a
     * caller that will try somewhere else on failure.
     */
    public static String postFormOnce(URI uri, String body)
            throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        final HttpResponse<String> response =
                FAILOVER_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        final int status = response.statusCode();
        if (status >= 200 && status < 300) return response.body();
        if (status == 429) throw new RateLimitedException(uri.getHost(), response.body());
        throw new HttpStatusException(status, uri, response.body());
    }

    /**
     * Downloads to {@code target} through a {@code .part} file that is moved into
     * place only on success, so an interrupted transfer never leaves a truncated
     * file that later looks like a valid cache entry.
     *
     * <p>TODO: report progress from the response body rather than only at the
     * end - {@code BodyHandlers.ofFile} gives no intermediate callbacks, so this
     * needs a counting {@code BodySubscriber}.</p>
     */
    public static void download(URI uri, Path target, ProgressListener listener)
            throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        final Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            final HttpResponse<Path> response =
                    send(uri, HttpResponse.BodyHandlers.ofFile(part));
            final long size = Files.size(part);
            if (size == 0) {
                throw new IOException("empty response from " + uri
                        + " - the request probably matched no GRIB records");
            }
            if (listener != null) listener.onProgress(size, size);
            LOG.fine(() -> "downloaded " + size + " bytes from " + response.uri());
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        finally {
            Files.deleteIfExists(part);
        }
    }

    private static <T> HttpResponse<T> send(URI uri, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return sendWithRetry(request, handler);
    }

    /**
     * Retries 5xx and transport failures, never 4xx: a 400 from NOMADS means the
     * request itself is wrong (an unavailable variable, say) and repeating it
     * only wastes the server's time.
     */
    /**
     * Retries 5xx and transport failures, never 4xx: a 400 from NOMADS means the
     * request itself is wrong (an unavailable variable, say) and repeating it
     * only wastes the server's time.
     *
     * <p>That is what it always claimed to do. It did the opposite: the throws
     * for 4xx and 429 stood inside the {@code try}, so the {@code catch} on the
     * next line caught them, filed them as the attempt's failure and went round
     * again. Three requests were sent for every rejection, each identical to the
     * one already refused, and the log said "retrying" over an error that could
     * not change. Permanent failures now leave the loop rather than falling into
     * their own handler.</p>
     */
    private static <T> HttpResponse<T> sendWithRetry(HttpRequest request,
                                                     HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                final HttpResponse<T> response = CLIENT.send(request, handler);
                final int status = response.statusCode();
                if (status >= 200 && status < 300) return response;

                // Overpass answers 429 when the client is over its slot
                // allowance. Retrying is exactly the wrong response, and the
                // bare status is not a useful thing to show a user.
                if (status == 429) {
                    throw new RateLimitedException(request.uri().getHost(), bodyOf(response));
                }
                final HttpStatusException failure =
                        new HttpStatusException(status, request.uri(), bodyOf(response));
                if (failure.isPermanent()) throw failure;
                last = failure;
            }
            catch (HttpStatusException | RateLimitedException e) {
                throw e;   // asking again cannot change the answer
            }
            catch (IOException e) {
                last = e;
            }
            if (attempt < MAX_ATTEMPTS) {
                LOG.warning("attempt " + attempt + " failed for " + request.uri() + "; retrying");
                Thread.sleep(Duration.ofSeconds(2L * attempt).toMillis());
            }
        }
        throw last;
    }

    /**
     * The response body as text, for an error worth explaining.
     *
     * <p>A download's body is a {@link Path} rather than a string, and on a
     * failure that file holds the server's message; a little of it is read back
     * rather than reporting the pathname, which explains nothing.</p>
     */
    private static String bodyOf(HttpResponse<?> response) {
        final Object body = response.body();
        if (body instanceof String s) return s;
        if (body instanceof Path file) {
            try {
                final byte[] head = Files.readAllBytes(file);
                final int take = Math.min(head.length, 1024);
                return new String(head, 0, take, java.nio.charset.StandardCharsets.UTF_8);
            }
            catch (IOException e) {
                return "";
            }
        }
        return "";
    }

    /**
     * Percent-encodes a query parameter value.
     *
     * <p>{@link java.net.URLEncoder} is not used because it encodes a space as
     * {@code +}, which is correct for form bodies and wrong inside a path or a
     * value that a CGI compares literally.</p>
     */
    public static String encode(String value) {
        final StringBuilder out = new StringBuilder(value.length() + 8);
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            final int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            }
            else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }
}
