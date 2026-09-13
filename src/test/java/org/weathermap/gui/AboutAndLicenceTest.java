package org.weathermap.gui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two things a released build has to carry: who made it, and under what
 * terms. Both are easy to drop in an edit and invisible until someone looks.
 */
class AboutAndLicenceTest {

    @Test
    void theAboutBoxAttributesTheApplication() {
        final String html = MainWindow.aboutHtml("1.0.0", "built-in GRIB2 reader");

        assertTrue(html.contains("Saigon Weather"), html);
        assertTrue(html.contains("Del 2026"), html);
        assertTrue(html.contains("https://www.facebook.com/saigonweather/"), html);
        assertTrue(html.contains("Weather Map 1.0.0"), html);
        assertTrue(html.contains("built-in GRIB2 reader"), html);
    }

    /** Section 5 of the GPL asks an interactive program to say so. */
    @Test
    void theAboutBoxCarriesTheLicenceNotice() {
        final String html = MainWindow.aboutHtml("1.0.0", "reader");

        assertTrue(html.contains("GNU General Public License"), html);
        assertTrue(html.contains("version 3"), html);
        assertTrue(html.toLowerCase().contains("no warranty"), html);
    }

    /** A development run has no manifest version; the heading must not say "null". */
    @Test
    void anUnknownVersionIsOmittedRatherThanPrinted() {
        final String html = MainWindow.aboutHtml("", "reader");

        assertTrue(html.contains("<b>Weather Map</b>"), html);
        assertFalse(html.contains("null"));
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }

    /**
     * The licence must be in the jar, not merely in the repository: Help shows
     * it from the classpath, and a package that ships without it states no terms.
     */
    @Test
    void theLicenceIsBundledAndIsTheGpl() throws Exception {
        try (var in = HelpWindow.class.getResourceAsStream("/help/LICENSE")) {
            assertNotNull(in, "/help/LICENSE is not in the jar - check the pom's <resources>");
            final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(text.contains("GNU GENERAL PUBLIC LICENSE"), "not the GPL");
            assertTrue(text.contains("Version 3, 29 June 2007"), "not version 3");
            assertTrue(text.contains("END OF TERMS AND CONDITIONS"), "truncated");
            // The published gpl-3.0.txt, unmodified.
            assertEquals(35149, text.getBytes(StandardCharsets.UTF_8).length,
                         "the licence text has been altered");
        }
    }

    @Test
    void bothGuidesOfferTheLicence() {
        assertTrue(HelpWindow.USER_GUIDE.stream()
                .anyMatch(p -> p.resource().equals("/help/LICENSE") && p.plainText()));
        assertTrue(HelpWindow.DEVELOPER_GUIDE.stream()
                .anyMatch(p -> p.resource().equals("/help/LICENSE") && p.plainText()));
    }
}
