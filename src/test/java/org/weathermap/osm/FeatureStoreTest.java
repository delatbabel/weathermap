package org.weathermap.osm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weathermap.model.BoundingBox;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parsed form of a cached Overpass response.
 *
 * <p>It exists because caching the bytes but not the work left three seconds
 * of parsing on every press. It is a derivation, so the rule it has to keep is
 * that anything wrong with it produces null and a re-parse, never a wrong map.
 */
class FeatureStoreTest {

    private static final BoundingBox AREA = BoundingBox.of(-10, 100, 20, 120);

    private static FeatureStore.Extract extract(List<Feature> features) {
        return new FeatureStore.Extract(
                new FeatureStore.Header(AREA,
                        List.of(FeatureKind.COASTLINE, FeatureKind.PLACE, FeatureKind.BOUNDARY),
                        "city|town"),
                features);
    }

    private static final List<Feature> SAMPLE = List.of(
            new Feature(FeatureKind.COASTLINE,
                    List.of(new double[]{10.123456, 107.654321},
                            new double[]{-10.5, -107.5},
                            new double[]{0, 0}),
                    Map.of("natural", "coastline")),
            new Feature(FeatureKind.PLACE,
                    List.of(new double[]{10.346, 107.084}),
                    Map.of("name", "Vũng Tàu", "place", "town", "population", "450000")),
            new Feature(FeatureKind.BOUNDARY,
                    List.of(new double[]{89.999999, 179.999999},
                            new double[]{-89.999999, -179.999999}),
                    Map.of("boundary", "administrative", "admin_level", "2")));

    @Test
    void writesAndReadsBackEverythingThatMatters(@TempDir Path dir) {
        final Path file = dir.resolve("features.bin");
        assertTrue(FeatureStore.write(extract(SAMPLE), file));

        final List<Feature> back = FeatureStore.read(file).features();
        assertEquals(SAMPLE.size(), back.size());
        for (int i = 0; i < SAMPLE.size(); i++) {
            assertEquals(SAMPLE.get(i).kind(), back.get(i).kind(), "kind " + i);
            assertEquals(SAMPLE.get(i).tags(), back.get(i).tags(), "tags " + i);
            assertEquals(SAMPLE.get(i).points().size(), back.get(i).points().size());
        }
    }

    /**
     * Microdegrees, so a tenth of a metre. Finer than OSM is surveyed and
     * finer than any chart here can draw, but worth pinning: a float would
     * have been quietly ten times worse at 180 degrees.
     */
    @Test
    void keepsCoordinatesToATenthOfAMetre(@TempDir Path dir) {
        final Path file = dir.resolve("features.bin");
        FeatureStore.write(extract(SAMPLE), file);
        final List<Feature> back = FeatureStore.read(file).features();

        double worst = 0;
        for (int i = 0; i < SAMPLE.size(); i++) {
            final List<double[]> was = SAMPLE.get(i).points();
            final List<double[]> now = back.get(i).points();
            for (int p = 0; p < was.size(); p++) {
                worst = Math.max(worst, Math.abs(was.get(p)[0] - now.get(p)[0]));
                worst = Math.max(worst, Math.abs(was.get(p)[1] - now.get(p)[1]));
            }
        }
        assertTrue(worst <= 5e-7, "worst coordinate error " + worst + " deg");
    }

    @Test
    void poolsTheTagStringsThatRepeat(@TempDir Path dir) throws Exception {
        final List<Feature> many = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            many.add(new Feature(FeatureKind.COASTLINE,
                    List.of(new double[]{i / 100.0, i / 100.0}, new double[]{0, 0}),
                    Map.of("natural", "coastline")));
        }
        final Path file = dir.resolve("many.bin");
        FeatureStore.write(extract(many), file);

        // Two points is sixteen bytes; the tags must not be costing more than
        // the geometry they describe.
        assertTrue(Files.size(file) < 2000 * 40,
                   "2000 identically tagged ways came to " + Files.size(file) + " bytes");
        assertEquals(2000, FeatureStore.read(file).features().size());
    }

    @Test
    void anEmptyListRoundTrips(@TempDir Path dir) {
        final Path file = dir.resolve("empty.bin");
        assertTrue(FeatureStore.write(extract(List.of()), file));
        assertEquals(List.of(), FeatureStore.read(file).features());
    }

    @Test
    void leavesNoPartFileBehind(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("features.bin");
        FeatureStore.write(extract(SAMPLE), file);
        try (var entries = Files.list(dir)) {
            assertEquals(List.of("features.bin"),
                         entries.map(f -> f.getFileName().toString()).sorted().toList());
        }
    }

    // ---- everything that should give up ------------------------------------

    @Test
    void aMissingFileIsNotAnError(@TempDir Path dir) {
        assertNull(FeatureStore.read(dir.resolve("absent.bin")));
    }

    @Test
    void somethingElseEntirelyIsRefused(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("not-ours.bin");
        Files.writeString(file, "<?xml version=\"1.0\"?><osm/>");
        assertNull(FeatureStore.read(file));
    }

    @Test
    void anEmptyFileIsRefused(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("empty-file.bin");
        Files.write(file, new byte[0]);
        assertNull(FeatureStore.read(file));
    }

    /**
     * A write cut short by a full disk or a kill must not read back as a
     * shorter map. The {@code .part} rename is the first defence and this is
     * the second.
     */
    @Test
    void aTruncatedFileIsRefused(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("features.bin");
        FeatureStore.write(extract(SAMPLE), file);

        final byte[] whole = Files.readAllBytes(file);
        for (int keep : new int[]{4, 8, 20, whole.length / 2, whole.length - 1}) {
            final Path cut = dir.resolve("cut-" + keep + ".bin");
            Files.write(cut, java.util.Arrays.copyOf(whole, keep));
            assertNull(FeatureStore.read(cut), "read " + keep + " bytes as a whole file");
        }
    }

    /** An older format is rebuilt, not reinterpreted. */
    @Test
    void aFileFromAnotherVersionIsRefused(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("old.bin");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.write(new byte[]{'W', 'M', 'F', 'C'});
            out.writeInt(999);
        }
        assertNull(FeatureStore.read(file));
    }

    @Test
    void aKindThisBuildDoesNotHaveIsRefused(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("future.bin");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.write(new byte[]{'W', 'M', 'F', 'C'});
            out.writeInt(2);
            out.writeInt(-10_000_000);    // the area
            out.writeInt(100_000_000);
            out.writeInt(20_000_000);
            out.writeInt(120_000_000);
            out.writeByte(1);
            out.writeByte(120);           // of a kind from the future
            out.writeUTF("");
        }
        assertNull(FeatureStore.read(file));
    }

    @Test
    void writingSomewhereImpossibleIsReportedRatherThanThrown(@TempDir Path dir)
            throws Exception {
        // A file where the directory would have to be.
        final Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        assertFalse(FeatureStore.write(extract(SAMPLE), blocked.resolve("features.bin")));
    }
}
