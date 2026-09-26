package org.weathermap.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An on-disk cache under {@code ~/.weathermap/cache}, keyed by a hash of the
 * request that produced the bytes.
 *
 * <p>It exists for two different reasons in the two sources it serves, and the
 * expiry policy differs accordingly:</p>
 *
 * <ul>
 *   <li><b>OSM features</b> change slowly and are expensive to fetch - an
 *       Overpass query for a country-sized box takes tens of seconds and is
 *       rate-limited. Cached for weeks.</li>
 *   <li><b>GRIB files</b> are immutable once published: a given run, forecast
 *       hour and subregion will never change. They are cached until the disk
 *       budget evicts them, not until they expire.</li>
 *   <li><b>Tide predictions</b> are not expensive to compute but they are
 *       <em>rationed</em>: Storm Glass counts every request against a daily
 *       quota. Cached for a day, which is a ceiling on requests rather than a
 *       guess at how fast the answer changes - see
 *       {@link org.weathermap.tide.StormglassClient#TTL}.</li>
 * </ul>
 *
 * <p>Not thread-safe against concurrent writers of the same key; the download
 * path relies on {@link Http#download} writing through a {@code .part} file so a
 * partial entry is never visible.</p>
 */
public final class Cache {

    private static final Logger LOG = Logger.getLogger(Cache.class.getName());

    /** How long an OSM extract is reused before being fetched again. */
    public static final Duration OSM_TTL = Duration.ofDays(28);

    private final Path root;

    public Cache() {
        this(Path.of(System.getProperty("user.home"),
                     org.weathermap.model.Preferences.CONFIG_DIR, "cache"));
    }

    public Cache(Path root) {
        this.root = root;
    }

    public Path root() { return root; }

    /**
     * The path an entry would occupy. Does not create it.
     *
     * @param namespace a subdirectory - {@code "osm"}, {@code "grib"} or {@code "tide"}
     * @param key       the request this entry caches; hashed, so it may be long
     * @param extension file extension including the dot
     */
    public Path pathFor(String namespace, String key, String extension) {
        return root.resolve(namespace).resolve(sha256(key) + extension);
    }

    /** True when the entry exists, is non-empty, and is within {@code ttl}. */
    public boolean isFresh(Path entry, Duration ttl) {
        try {
            if (!Files.isReadable(entry) || Files.size(entry) == 0) return false;
            if (ttl == null) return true;                       // immutable content
            final Instant modified = Files.getLastModifiedTime(entry).toInstant();
            return modified.plus(ttl).isAfter(Instant.now());
        }
        catch (IOException e) {
            return false;
        }
    }

    /** Files being written. Transient, and deleting one fails a download. */
    private static final String PARTIAL = ".part";

    /** A response and the features parsed out of it are one entry, not two. */
    private static final String RESPONSE = ".osm.xml";
    private static final String DERIVED = ".features.bin";

    /** What an eviction pass did. */
    public record Evicted(long bytesBefore, long bytesAfter, int filesDeleted) {

        public long bytesFreed() { return bytesBefore - bytesAfter; }

        public boolean didAnything() { return filesDeleted > 0; }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "cache %.0f MB -> %.0f MB, %d file(s) evicted",
                    bytesBefore / 1e6, bytesAfter / 1e6, filesDeleted);
        }
    }

    /** What the cache currently occupies, in bytes. */
    public long size() {
        try {
            if (!Files.isDirectory(root)) return 0;
            try (var walk = Files.walk(root)) {
                return walk.filter(Files::isRegularFile).mapToLong(Cache::sizeOf).sum();
            }
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not measure " + root, e);
            return 0;
        }
    }

    /**
     * Deletes the oldest entries until the cache is under {@code maxBytes}.
     *
     * <h2>Oldest fetched, not least recently used</h2>
     *
     * <p>Which is a deliberate second best. Real LRU wants the time an entry
     * was last <em>read</em>, and the obvious way to record that - touch the
     * file on a cache hit - cannot be done here, because the modification
     * time is also what {@link #isFresh} measures a TTL against. Touching on
     * read would make a busy entry immortal and quietly disable expiry
     * altogether. Oldest-fetched-first is at least aligned with that: the
     * entry nearest its own expiry is the one to go.</p>
     *
     * <h2>What is left alone</h2>
     *
     * <p>A {@code .part} file is a download in progress. Deleting one fails
     * the transfer that is writing it, and it will be renamed away in a
     * moment anyway, so it is neither counted nor touched.</p>
     *
     * <p>An OSM response and the features parsed out of it go together. They
     * are written moments apart, so which of the pair sorts first is
     * arbitrary, and evicting only one of them either frees a tenth of what
     * was wanted or leaves a ten-megabyte derivation of a response that is no
     * longer there.</p>
     *
     * <p>Entries may be read by another thread while this runs. Every reader
     * here answers a missing or truncated file by fetching or parsing again,
     * so the worst case is work repeated, not a wrong answer.</p>
     *
     * @param maxBytes the budget; zero or less means no limit and does nothing
     */
    public Evicted evictTo(long maxBytes) {
        try {
            if (!Files.isDirectory(root)) return new Evicted(0, 0, 0);

            final List<Path> files;
            try (var walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile)
                        .filter(f -> !f.getFileName().toString().endsWith(PARTIAL))
                        .sorted(Comparator.comparing(Cache::modifiedTime))
                        .toList();
            }
            long total = 0;
            for (Path f : files) total += sizeOf(f);
            final long before = total;
            if (maxBytes <= 0 || total <= maxBytes) return new Evicted(before, before, 0);

            int deleted = 0;
            for (Path f : files) {
                if (total <= maxBytes) break;
                total -= delete(f);
                deleted++;
                for (Path companion : companionsOf(f)) {
                    total -= delete(companion);
                    deleted++;
                }
            }
            final Evicted result = new Evicted(before, total, deleted);
            LOG.info(result::toString);
            return result;
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Cache eviction failed", e);
            return new Evicted(0, 0, 0);
        }
    }

    /** The files that only make sense alongside {@code entry}. */
    private static List<Path> companionsOf(Path entry) {
        final String name = entry.getFileName().toString();
        if (name.endsWith(RESPONSE)) {
            return List.of(entry.resolveSibling(
                    name.substring(0, name.length() - RESPONSE.length()) + DERIVED));
        }
        if (name.endsWith(DERIVED)) {
            return List.of(entry.resolveSibling(
                    name.substring(0, name.length() - DERIVED.length()) + RESPONSE));
        }
        return List.of();
    }

    /** @return the bytes freed, or 0 if it was not there */
    private static long delete(Path file) {
        try {
            final long size = Files.isRegularFile(file) ? Files.size(file) : 0;
            if (Files.deleteIfExists(file)) {
                LOG.fine(() -> "evicted " + file);
                return size;
            }
            return 0;
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not evict " + file, e);
            return 0;
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        }
        catch (IOException e) {
            return 0;
        }
    }

    /**
     * Runs {@link #evictTo} on a background thread, at most one pass at a time.
     *
     * <p>Safe to call from anywhere that has just added to the cache, and from
     * startup. Walking a thousand files takes single-digit milliseconds, but
     * it is file I/O and the callers are the event thread and the end of a
     * download, neither of which should be waiting on it.</p>
     */
    public void evictInBackground(long maxBytes) {
        if (maxBytes <= 0 || !evicting.compareAndSet(false, true)) return;
        final Thread worker = new Thread(() -> {
            try {
                evictTo(maxBytes);
            }
            finally {
                evicting.set(false);
            }
        }, "cache-evict");
        worker.setDaemon(true);
        worker.start();
    }

    private final java.util.concurrent.atomic.AtomicBoolean evicting =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static Instant modifiedTime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        }
        catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private static String sha256(String s) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            final StringBuilder out = new StringBuilder(64);
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.substring(0, 32);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
