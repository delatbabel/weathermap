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
 * <p><b>The User-Agent is not decoration.</b> Both services this application
 * talks to police it. The OSM Foundation's Overpass instances and tile servers
 * block requests from clients that do not identify themselves, and NOMADS
 * throttles by client. A real contact address belongs in
 * {@link #USER_AGENT} before this is pointed at a public endpoint.</p>
 */
public final class Http {

    private static final Logger LOG = Logger.getLogger(Http.class.getName());

    /** TODO: put a real project URL or contact address here before public use. */
    public static final String USER_AGENT = "weathermap/0.1 (+https://example.invalid/weathermap)";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 3;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() { }

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
    private static <T> HttpResponse<T> sendWithRetry(HttpRequest request,
                                                     HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                final HttpResponse<T> response = CLIENT.send(request, handler);
                final int status = response.statusCode();
                if (status >= 200 && status < 300) return response;
                if (status < 500) {
                    throw new IOException("HTTP " + status + " from " + request.uri());
                }
                last = new IOException("HTTP " + status + " from " + request.uri());
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
