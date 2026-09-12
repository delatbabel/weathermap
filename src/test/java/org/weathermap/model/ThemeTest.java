package org.weathermap.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThemeTest {

    @Test
    void themesRoundTripThroughTheirStoredId() {
        for (Theme theme : Theme.values()) {
            assertEquals(theme, Theme.byId(theme.id()));
        }
    }

    /** A preferences file edited by hand should still open a usable window. */
    @Test
    void anythingUnrecognisedFallsBackToLight() {
        assertEquals(Theme.LIGHT, Theme.byId(null));
        assertEquals(Theme.LIGHT, Theme.byId(""));
        assertEquals(Theme.LIGHT, Theme.byId("solarized"));
    }

    @Test
    void theIdIsCaseAndSpaceInsensitive() {
        assertEquals(Theme.DARK, Theme.byId("  DARK "));
    }

    @Test
    void theSystemLookAndFeelIsOneOfTheChoices() {
        assertEquals(Theme.SYSTEM, Theme.byId("system"));
        assertEquals(3, Theme.values().length,
                     "the Appearance menu is built from values(), so a fourth "
                     + "entry here is a fourth menu item");
    }
}
