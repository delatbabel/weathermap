package org.weathermap.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiLayoutTest {

    @Test
    void aLayoutSurvivesBeingWrittenAndReadBack() {
        final UiLayout layout = new UiLayout(120, 60, 1440, 900, 980, 260);
        assertEquals(layout, UiLayout.parse(layout.toString()));
    }

    @Test
    void nothingStoredGivesTheDefault() {
        assertEquals(UiLayout.DEFAULT, UiLayout.parse(null));
        assertEquals(UiLayout.DEFAULT, UiLayout.parse(""));
        assertEquals(UiLayout.DEFAULT, UiLayout.parse("  "));
    }

    @Test
    void anUnreadableLayoutGivesTheDefaultRatherThanThrowing() {
        assertEquals(UiLayout.DEFAULT, UiLayout.parse("1280x800"));
        assertEquals(UiLayout.DEFAULT, UiLayout.parse("1,2,3"));
        assertEquals(UiLayout.DEFAULT, UiLayout.parse("a,b,c,d,e,f"));
    }

    /**
     * A window saved while minimised reports a few pixels on some platforms.
     * Restoring that hands back a window too small to find the controls in,
     * which reads as the application failing to start.
     */
    @Test
    void anAbsurdlySmallStoredSizeIsIgnoredButThePositionIsKept() {
        final UiLayout restored = UiLayout.parse("40,50,2,1,-1,-1");

        assertEquals(UiLayout.DEFAULT.width(), restored.width());
        assertEquals(UiLayout.DEFAULT.height(), restored.height());
        assertEquals(40, restored.x());
        assertEquals(50, restored.y());
    }

    @Test
    void sizeAndPositionAreReportedSeparately() {
        assertFalse(UiLayout.DEFAULT.hasPosition(), "never been placed");
        assertTrue(UiLayout.DEFAULT.hasSize(), "but always has a size to open at");
        assertTrue(new UiLayout(0, 0, 900, 700, -1, -1).hasPosition(),
                   "the top-left corner of a screen is a position");
    }
}
