package org.weathermap.cli;

import org.junit.jupiter.api.Test;
import org.weathermap.model.GribModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherMapCliTest {

    @Test
    void parsesTheOptionsItDocuments() {
        final WeatherMapCli.Options o = WeatherMapCli.Options.parse(new String[]{
                "--cli", "--area", "-11,49.5,2,61", "--model", "gfs_0p50",
                "--var", "TMP,APCP", "--level", "surface", "--hours", "0,6,12-18",
                "--size", "800x600", "--mercator", "--quiet"});

        assertEquals(GribModel.GFS_0P50, o.model);
        assertEquals(java.util.List.of("TMP", "APCP"), o.variables);
        assertEquals(java.util.List.of(0, 6, 12, 13, 14, 15, 16, 17, 18), o.hours);
        assertEquals(800, o.width);
        assertEquals(600, o.height);
        assertTrue(o.mercator);
        assertTrue(o.quiet);
        assertEquals(49.5, o.area.south());
    }

    @Test
    void rejectsWhatItCannotActOn() {
        assertThrows(IllegalArgumentException.class,
                () -> WeatherMapCli.Options.parse(new String[]{"--model", "not-a-model"}));
        assertThrows(IllegalArgumentException.class,
                () -> WeatherMapCli.Options.parse(new String[]{"--hours", "12-6"}));
        assertThrows(IllegalArgumentException.class,
                () -> WeatherMapCli.Options.parse(new String[]{"--area"}));
        assertThrows(IllegalArgumentException.class,
                () -> WeatherMapCli.Options.parse(new String[]{"--nonsense"}));
    }

    @Test
    void aRenameIsSplitOnItsFirstEquals() {
        final WeatherMapCli.Options options = WeatherMapCli.Options.parse(
                new String[]{"--rename", "South China Sea=East Sea"});

        assertEquals(1, options.renames.size());
        assertEquals("South China Sea", options.renames.get(0)[0]);
        assertEquals("East Sea", options.renames.get(0)[1]);
    }

    @Test
    void severalRenamesAreKept() {
        final WeatherMapCli.Options options = WeatherMapCli.Options.parse(new String[]{
                "--rename", "South China Sea=East Sea",
                "--rename", "Gulf of Thailand=Gulf of Siam"});

        assertEquals(2, options.renames.size());
    }

    /** A rename with no new name, or no old one, is a typo rather than a request. */
    @Test
    void aRenameNeedsBothHalves() {
        assertThrows(IllegalArgumentException.class,
                     () -> WeatherMapCli.Options.parse(new String[]{"--rename", "nonsense"}));
        assertThrows(IllegalArgumentException.class,
                     () -> WeatherMapCli.Options.parse(new String[]{"--rename", "=East Sea"}));
        assertThrows(IllegalArgumentException.class,
                     () -> WeatherMapCli.Options.parse(new String[]{"--rename", "South China Sea="}));
    }
}
