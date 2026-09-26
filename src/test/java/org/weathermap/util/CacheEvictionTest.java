package org.weathermap.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache is unbounded without this, and it is not a small one: a few
 * months of ordinary use reached 943 MB, most of it single Overpass responses
 * of a hundred megabytes and more.
 */
class CacheEvictionTest {

    /** Writes {@code bytes} bytes under {@code name}, fetched {@code ageMinutes} ago. */
    private static Path entry(Path root, String name, int bytes, int ageMinutes)
            throws IOException {
        final Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
        Files.setLastModifiedTime(file,
                FileTime.from(Instant.now().minusSeconds(ageMinutes * 60L)));
        return file;
    }

    @Test
    void measuresWhatItHolds(@TempDir Path dir) throws Exception {
        final Cache cache = new Cache(dir);
        assertEquals(0, cache.size(), "nothing yet, and no directory either");

        entry(dir, "grib/a.grib2", 1000, 10);
        entry(dir, "osm/b.osm.xml", 2000, 10);
        assertEquals(3000, cache.size());
    }

    @Test
    void takesTheOldestFirstUntilItIsUnderBudget(@TempDir Path dir) throws Exception {
        final Path oldest = entry(dir, "grib/old.grib2", 1000, 300);
        final Path middle = entry(dir, "grib/middle.grib2", 1000, 200);
        final Path newest = entry(dir, "grib/new.grib2", 1000, 100);

        final Cache.Evicted evicted = new Cache(dir).evictTo(1500);

        assertFalse(Files.exists(oldest));
        assertFalse(Files.exists(middle));
        assertTrue(Files.exists(newest), "the newest survives");
        assertEquals(3000, evicted.bytesBefore());
        assertEquals(1000, evicted.bytesAfter());
        assertEquals(2, evicted.filesDeleted());
    }

    @Test
    void doesNothingWhenItIsAlreadySmallEnough(@TempDir Path dir) throws Exception {
        final Path file = entry(dir, "grib/a.grib2", 1000, 10);
        final Cache.Evicted evicted = new Cache(dir).evictTo(5000);

        assertTrue(Files.exists(file));
        assertFalse(evicted.didAnything());
        assertEquals(evicted.bytesBefore(), evicted.bytesAfter());
    }

    @Test
    void aBudgetOfZeroMeansNoLimit(@TempDir Path dir) throws Exception {
        final Path file = entry(dir, "grib/a.grib2", 5000, 999);
        assertFalse(new Cache(dir).evictTo(0).didAnything());
        assertFalse(new Cache(dir).evictTo(-1).didAnything());
        assertTrue(Files.exists(file));
    }

    /**
     * A response and the features parsed out of it are one entry. They are
     * written moments apart, so which sorts first is arbitrary - and taking
     * only one of them either frees a tenth of what was wanted or leaves a
     * derivation of a response that is no longer there.
     */
    @Test
    void anOsmResponseAndItsParsedFormGoTogether(@TempDir Path dir) throws Exception {
        final Path xml = entry(dir, "osm/abc.osm.xml", 10_000, 300);
        final Path bin = entry(dir, "osm/abc.features.bin", 1000, 299);
        final Path keep = entry(dir, "grib/new.grib2", 1000, 10);

        final Cache.Evicted evicted = new Cache(dir).evictTo(5000);

        assertFalse(Files.exists(xml), "the response went");
        assertFalse(Files.exists(bin), "and so did what was parsed out of it");
        assertTrue(Files.exists(keep));
        assertEquals(2, evicted.filesDeleted());
    }

    @Test
    void theParsedFormSortingFirstTakesTheResponseWithIt(@TempDir Path dir) throws Exception {
        // The other way round, since the order between the two is arbitrary.
        final Path bin = entry(dir, "osm/abc.features.bin", 1000, 300);
        final Path xml = entry(dir, "osm/abc.osm.xml", 10_000, 299);
        entry(dir, "grib/new.grib2", 1000, 10);

        new Cache(dir).evictTo(5000);

        assertFalse(Files.exists(bin));
        assertFalse(Files.exists(xml), "or a 10 MB orphan is left behind");
    }

    /**
     * A {@code .part} file is a download in progress. Deleting one fails the
     * transfer writing it, and it will be renamed away in a moment.
     */
    @Test
    void leavesDownloadsInProgressAlone(@TempDir Path dir) throws Exception {
        final Path partial = entry(dir, "grib/busy.grib2.part", 10_000, 999);
        final Path old = entry(dir, "grib/old.grib2", 1000, 300);

        final Cache.Evicted evicted = new Cache(dir).evictTo(500);

        assertTrue(Files.exists(partial), "still being written");
        assertFalse(Files.exists(old));
        assertEquals(1000, evicted.bytesBefore(), "and not counted towards the budget");
    }

    @Test
    void aMissingCacheDirectoryIsNotAnError(@TempDir Path dir) {
        final Cache.Evicted evicted = new Cache(dir.resolve("never-created")).evictTo(100);
        assertFalse(evicted.didAnything());
    }

    @Test
    void theBackgroundPassDoesTheSameThing(@TempDir Path dir) throws Exception {
        final Path old = entry(dir, "grib/old.grib2", 4000, 300);
        entry(dir, "grib/new.grib2", 1000, 10);

        final Cache cache = new Cache(dir);
        cache.evictInBackground(2000);

        final long deadline = System.currentTimeMillis() + 10_000;
        while (Files.exists(old) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(Files.exists(old), "the background pass never ran");
        assertEquals(1000, cache.size());
    }

    @Test
    void theBackgroundPassIgnoresABudgetOfZero(@TempDir Path dir) throws Exception {
        final Path file = entry(dir, "grib/a.grib2", 5000, 999);
        new Cache(dir).evictInBackground(0);
        Thread.sleep(100);
        assertTrue(Files.exists(file));
    }

    @Test
    void theSummaryReadsAsSomethingToPutInALog(@TempDir Path dir) throws Exception {
        entry(dir, "grib/old.grib2", 4_000_000, 300);
        entry(dir, "grib/new.grib2", 1_000_000, 10);

        final String said = new Cache(dir).evictTo(2_000_000).toString();
        assertTrue(said.contains("5 MB"), said);
        assertTrue(said.contains("1 MB"), said);
        assertTrue(said.contains("1 file(s) evicted"), said);
    }
}
