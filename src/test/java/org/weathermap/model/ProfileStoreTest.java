package org.weathermap.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileStoreTest {

    private static GribSelection selection() {
        final GribSelection sel = new GribSelection();
        sel.setModel(GribModel.GFS_0P25);
        sel.setVariables(Set.of(GribCatalog.WIND, GribCatalog.PRESSURE_MSL));
        sel.setLevels(Set.of(GribCatalog.LEVEL_10M, GribCatalog.LEVEL_MSL));
        sel.setSeries(6, 24, 48);
        return sel;
    }

    @Test
    void aProfileComesBackAsItWentIn(@TempDir Path dir) throws Exception {
        final ProfileStore store = new ProfileStore(dir);
        final BoundingBox area = BoundingBox.of(49.5, -11, 60, 3);
        store.save("Channel", area, selection());

        final Profile back = store.load("Channel").orElseThrow();

        assertEquals("Channel", back.name());
        assertEquals(area.toString(), back.area().toString());
        assertEquals(GribModel.GFS_0P25, back.selection().model());
        assertEquals(Set.of(GribCatalog.WIND, GribCatalog.PRESSURE_MSL),
                     back.selection().variables());
        assertEquals(Set.of(GribCatalog.LEVEL_10M, GribCatalog.LEVEL_MSL),
                     back.selection().levels());
        // The series is the part most easily lost, being newest.
        assertTrue(back.selection().hasSeries());
        assertEquals(6, back.selection().seriesStepHours());
        assertEquals(24, back.selection().seriesHoursBack());
        assertEquals(48, back.selection().seriesSpanHours());
    }

    @Test
    void namesAreListedInOrderAndReadBackAsTyped(@TempDir Path dir) throws Exception {
        final ProfileStore store = new ProfileStore(dir);
        final BoundingBox area = BoundingBox.of(49.5, -11, 60, 3);
        store.save("North Sea", area, selection());
        store.save("biscay", area, selection());
        store.save("Åland approaches", area, selection());

        assertEquals(List.of("Åland approaches", "biscay", "North Sea"), store.names());
    }

    /**
     * The file name is sanitised but the typed name is not: a profile may be
     * called anything the user likes and still live on any filesystem.
     */
    @Test
    void anAwkwardNameIsStoredWholeInAFileThatIsNot(@TempDir Path dir) throws Exception {
        final ProfileStore store = new ProfileStore(dir);
        store.save("Ushant → Finisterre / offshore",
                   BoundingBox.of(43, -10, 49, -2), selection());

        assertEquals(List.of("Ushant → Finisterre / offshore"), store.names());
        assertEquals("Ushant → Finisterre / offshore",
                     store.load("Ushant → Finisterre / offshore").orElseThrow().name());

        try (var files = Files.list(dir)) {
            final String fileName = files.findFirst().orElseThrow().getFileName().toString();
            assertFalse(fileName.contains("→"), fileName);
            assertFalse(fileName.contains("/"), fileName);
        }
    }

    @Test
    void savingTwiceReplacesRatherThanAccumulates(@TempDir Path dir) throws Exception {
        final ProfileStore store = new ProfileStore(dir);
        store.save("Channel", BoundingBox.of(49.5, -11, 60, 3), selection());
        store.save("Channel", BoundingBox.of(40, -20, 50, -5), selection());

        assertEquals(1, store.names().size());
        assertEquals(-20.0, store.load("Channel").orElseThrow().area().west(), 1e-9);
    }

    @Test
    void anUnknownProfileIsEmptyRatherThanAnError(@TempDir Path dir) {
        final ProfileStore store = new ProfileStore(dir);
        assertTrue(store.load("nothing here").isEmpty());
        assertFalse(store.exists("nothing here"));
        assertEquals(List.of(), store.names());
    }

    @Test
    void profilesCanBeDeleted(@TempDir Path dir) throws Exception {
        final ProfileStore store = new ProfileStore(dir);
        store.save("Channel", BoundingBox.of(49.5, -11, 60, 3), selection());

        assertTrue(store.delete("Channel"));
        assertFalse(store.delete("Channel"));
        assertEquals(List.of(), store.names());
    }

    /** Names differing only in case are one profile, not two near-identical ones. */
    @Test
    void caseDoesNotMakeANewProfile(@TempDir Path dir) throws Exception {
        final ProfileStore store = new ProfileStore(dir);
        store.save("Channel", BoundingBox.of(49.5, -11, 60, 3), selection());

        assertTrue(store.exists("channel"));
        assertEquals(1, store.names().size());
    }

    @Test
    void aProfileNeedsAName() {
        assertThrows(IllegalArgumentException.class,
                     () -> new Profile("  ", BoundingBox.of(49.5, -11, 60, 3), selection()));
    }

    /** A profile stores the question, not the machine: no theme, no window size. */
    @Test
    void aProfileHoldsNothingAboutRendering(@TempDir Path dir) throws Exception {
        new ProfileStore(dir).save("Channel", BoundingBox.of(49.5, -11, 60, 3), selection());

        try (var files = Files.list(dir)) {
            final String content = Files.readString(files.findFirst().orElseThrow());
            assertFalse(content.contains("ui."), content);
            assertFalse(content.contains("render."), content);
        }
    }
}
