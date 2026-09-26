package org.weathermap.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreferencesTest {

    @Test
    void storesAndRestoresTheLastAreaAndSelection(@TempDir Path dir) {
        final Path file = dir.resolve("preferences.properties");

        final Preferences saved = new Preferences(file);
        final BoundingBox area = BoundingBox.of(49.5, -11, 61, 2);
        final GribSelection selection = new GribSelection();
        selection.setModel(GribModel.HRRR_3KM);
        selection.setVariables(java.util.Set.of(GribCatalog.PRESSURE_MSL));
        selection.setLevels(java.util.Set.of(GribCatalog.LEVEL_MSL));
        selection.setForecastHours(java.util.List.of(0, 3, 6));
        saved.setArea(area);
        saved.setSelection(selection);
        saved.save();

        final Preferences reloaded = new Preferences(file);
        assertTrue(reloaded.hasArea());
        assertEquals(area, reloaded.area());
        assertEquals(GribModel.HRRR_3KM, reloaded.selection().model());
        assertEquals(java.util.List.of(0, 3, 6), reloaded.selection().forecastHours());
        assertEquals(java.util.Set.of(GribCatalog.LEVEL_MSL), reloaded.selection().levels());
    }

    @Test
    void missingFileYieldsDefaultsRatherThanFailing(@TempDir Path dir) {
        final Preferences prefs = new Preferences(dir.resolve("absent.properties"));
        assertFalse(prefs.hasArea());
        assertEquals(Preferences.DEFAULT_AREA, prefs.area());
    }

    @Test
    void unreadableValuesAreDroppedIndividually(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("preferences.properties");
        Files.writeString(file, """
                area.bbox=not a bounding box
                grib.model=gfs_0p50
                grib.forecastHours=0,not-a-number,12
                """);

        final Preferences prefs = new Preferences(file);
        // The bad area falls back to the default, but the good model survives -
        // one unreadable value must not discard the whole file.
        assertEquals(Preferences.DEFAULT_AREA, prefs.area());
        assertEquals(GribModel.GFS_0P50, prefs.selection().model());
        assertEquals(java.util.List.of(0, 12), prefs.selection().forecastHours());
    }

    @Test
    void themeAndWindowLayoutSurviveARestart() throws Exception {
        final java.nio.file.Path file = java.nio.file.Files.createTempFile("weathermap", ".properties");
        try {
            final Preferences saved = new Preferences(file);
            saved.setTheme(Theme.DARK);
            saved.setUiLayout(new UiLayout(10, 20, 1500, 950, 1100, 300));
            saved.save();

            final Preferences reopened = new Preferences(file);
            assertEquals(Theme.DARK, reopened.theme());
            assertEquals(new UiLayout(10, 20, 1500, 950, 1100, 300), reopened.uiLayout());
        }
        finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    /** A file written before these keys existed still opens a window. */
    @Test
    void anOlderPreferencesFileHasSensibleDefaults() {
        final Preferences fresh = new Preferences(
                java.nio.file.Path.of("/nonexistent/weathermap.properties"));
        assertEquals(Theme.LIGHT, fresh.theme());
        assertEquals(UiLayout.DEFAULT, fresh.uiLayout());
    }

    /**
     * The layer list is stored as the layers that are on, so a kind added later
     * is missing from every file written before it existed. Read literally that
     * says "the user turned this off", which would have silently denied isobars
     * to exactly the people who had used the application before.
     */
    @Test
    void aLayerAddedAfterTheFileWasWrittenIsOnNotOff() throws Exception {
        final java.nio.file.Path file = java.nio.file.Files.createTempFile("weathermap", ".properties");
        try {
            java.nio.file.Files.writeString(file,
                    "render.layers.v2=LAND_SEA,GRIB,COASTLINE,PLACE_LABELS,WIND_BARBS,ANNOTATION\n");

            final RenderSpec spec = new Preferences(file).renderSpec();

            // Not in the old list because it did not exist: keeps its default.
            assertTrue(spec.isEnabled(RenderSpec.LayerKind.ISOBARS));
            // In the old list: honoured.
            assertTrue(spec.isEnabled(RenderSpec.LayerKind.COASTLINE));
            // Known then and deliberately left out: still off.
            assertFalse(spec.isEnabled(RenderSpec.LayerKind.BOUNDARIES));
            assertFalse(spec.isEnabled(RenderSpec.LayerKind.GRATICULE));
        }
        finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void theCurrentLayerListIsTakenLiterally() throws Exception {
        final java.nio.file.Path file = java.nio.file.Files.createTempFile("weathermap", ".properties");
        try {
            java.nio.file.Files.writeString(file, "render.layers.v3=COASTLINE\n");

            final RenderSpec spec = new Preferences(file).renderSpec();
            assertTrue(spec.isEnabled(RenderSpec.LayerKind.COASTLINE));
            assertFalse(spec.isEnabled(RenderSpec.LayerKind.ISOBARS),
                        "a v3 file that omits it means the user turned it off");
        }
        finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void theChartTimeZoneSurvivesARestart() throws Exception {
        final java.nio.file.Path file = java.nio.file.Files.createTempFile("weathermap", ".properties");
        try {
            final Preferences saved = new Preferences(file);
            final RenderSpec spec = saved.renderSpec();
            spec.setZone(java.time.ZoneId.of("Asia/Bangkok"));
            saved.setRenderSpec(spec);
            saved.save();

            assertEquals(java.time.ZoneId.of("Asia/Bangkok"), new Preferences(file).zone());
        }
        finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    /**
     * The tz database changes and preferences files get copied between machines,
     * so a zone this machine has never heard of must not stop the application.
     */
    @Test
    void anUnknownTimeZoneFallsBackToTheSystemOne() throws Exception {
        final java.nio.file.Path file = java.nio.file.Files.createTempFile("weathermap", ".properties");
        try {
            java.nio.file.Files.writeString(file, "render.timeZone=Mars/Olympus\n");
            assertEquals(java.time.ZoneId.systemDefault(), new Preferences(file).zone());
        }
        finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void noStoredZoneMeansTheSystemOne() {
        assertEquals(java.time.ZoneId.systemDefault(),
                     new Preferences(java.nio.file.Path.of("/nonexistent/x.properties")).zone());
    }

    // ---- names printed instead of the map data's own ----------------------

    /**
     * A rename survives being stored, spaces and all.
     *
     * <p>Stored as numbered pairs rather than a key per name: a properties key
     * ends at its first unescaped space, so {@code render.labelName.South China
     * Sea} would come back as three different things. The name goes in the
     * value, where a space is ordinary and the file stays editable by hand.</p>
     */
    @Test
    void aLabelRenameSurvivesBeingStored(@TempDir Path dir) {
        final Path file = dir.resolve("preferences.properties");
        final Preferences saved = new Preferences(file);
        final RenderSpec spec = saved.renderSpec();
        spec.renameLabel("South China Sea", "East Sea");
        spec.renameLabel("Sea of Japan", "East Sea of Korea");
        saved.setRenderSpec(spec);
        saved.save();

        final RenderSpec back = new Preferences(file).renderSpec();
        assertEquals("East Sea", back.nameFor("South China Sea"));
        assertEquals("East Sea of Korea", back.nameFor("Sea of Japan"));
        assertEquals("Java Sea", back.nameFor("Java Sea"), "anything else is untouched");
    }

    /** One removed in the window has to disappear from the file, not linger. */
    @Test
    void aRenameThatIsTakenAwayStaysAway(@TempDir Path dir) {
        final Path file = dir.resolve("preferences.properties");
        final Preferences prefs = new Preferences(file);

        final RenderSpec first = prefs.renderSpec();
        first.renameLabel("South China Sea", "East Sea");
        first.renameLabel("Gulf of Thailand", "Gulf of Siam");
        prefs.setRenderSpec(first);
        prefs.save();

        final RenderSpec second = new Preferences(file).renderSpec();
        second.renameLabel("Gulf of Thailand", "");          // take it away
        final Preferences again = new Preferences(file);
        again.setRenderSpec(second);
        again.save();

        final RenderSpec back = new Preferences(file).renderSpec();
        assertEquals("East Sea", back.nameFor("South China Sea"));
        assertEquals("Gulf of Thailand", back.nameFor("Gulf of Thailand"));
    }

    @Test
    void aRenameToTheSameNameIsNoRenameAtAll() {
        final RenderSpec spec = new RenderSpec();
        spec.renameLabel("Java Sea", "Java Sea");
        spec.renameLabel("  ", "Something");

        assertTrue(spec.labelNames().isEmpty(), "" + spec.labelNames());
    }

    // ---- the Storm Glass API key -----------------------------------------

    @Test
    void storesAndReadsBackTheApiKey(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("prefs.properties");
        final Preferences prefs = new Preferences(file);
        assertEquals("", prefs.stormglassApiKey(), "nothing stored to begin with");

        prefs.setStormglassApiKey("  a-key-with-spaces-round-it  ");
        prefs.saveOrThrow();

        assertEquals("a-key-with-spaces-round-it", new Preferences(file).stormglassApiKey());
    }

    /** Clearing the field takes the key out of the file rather than storing "". */
    @Test
    void clearingTheKeyRemovesIt(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("cleared.properties");
        final Preferences prefs = new Preferences(file);
        prefs.setStormglassApiKey("a-key");
        prefs.setStormglassApiKey("");
        prefs.saveOrThrow();

        assertFalse(java.nio.file.Files.readString(file).contains("stormglass"),
                    java.nio.file.Files.readString(file));
        assertEquals("", new Preferences(file).stormglassApiKey());
    }

    /**
     * The file holds a credential now, so it should not be readable by every
     * account on the machine. Best effort, and skipped where the filesystem
     * has no such notion.
     */
    @Test
    void theFileIsWrittenOwnerOnly(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("permissions.properties");
        final Preferences prefs = new Preferences(file);
        prefs.setStormglassApiKey("a-key");
        prefs.saveOrThrow();

        final var view = java.nio.file.Files.getFileAttributeView(
                file, java.nio.file.attribute.PosixFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(view != null, "no POSIX permissions here");

        final var permissions = java.nio.file.Files.getPosixFilePermissions(file);
        assertFalse(permissions.contains(
                java.nio.file.attribute.PosixFilePermission.GROUP_READ), permissions.toString());
        assertFalse(permissions.contains(
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ), permissions.toString());
    }
}
