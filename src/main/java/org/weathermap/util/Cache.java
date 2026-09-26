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

    /**
     * Deletes least-recently-modified entries until the cache is under
     * {@code maxBytes}.
     *
     * <p>TODO: call this from the application on startup, or after each download,
     * once there is a configured budget. GRIB subsets are small individually but
     * a month of hourly HRRR runs is not.</p>
     */
    public void evictTo(long maxBytes) {
        try {
            if (!Files.isDirectory(root)) return;
            final List<Path> files;
            try (var walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Cache::modifiedTime))
                        .toList();
            }
            long total = 0;
            for (Path f : files) total += Files.size(f);
            for (Path f : files) {
                if (total <= maxBytes) break;
                final long size = Files.size(f);
                Files.deleteIfExists(f);
                total -= size;
                LOG.fine(() -> "evicted " + f);
            }
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Cache eviction failed", e);
        }
    }

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
