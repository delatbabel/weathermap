package org.weathermap.render;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Chart times carry an offset, never an abbreviation.
 *
 * <p>The abbreviations are not unique and are not international: BST is British
 * Summer Time and Bangladesh Standard Time, IST is India, Ireland and Israel.
 * A chart may be read anywhere, so it says GMT+7.</p>
 */
class ZoneLabelTest {

    private static final Instant WINTER = Instant.parse("2026-01-15T12:00:00Z");
    private static final Instant SUMMER = Instant.parse("2026-07-15T12:00:00Z");

    private static String label(String zone, Instant at) {
        return AnnotationLayer.zoneLabel(ZoneId.of(zone), at);
    }

    @Test
    void zeroOffsetIsCalledUtc() {
        assertEquals("UTC", label("UTC", SUMMER));
        assertEquals("UTC", label("Europe/London", WINTER));
    }

    @Test
    void wholeHoursDropTheMinutes() {
        assertEquals("GMT+7", label("Asia/Bangkok", SUMMER));
        assertEquals("GMT-5", label("America/New_York", WINTER));
    }

    /** Summer time changes the offset, and the label has to follow it. */
    @Test
    void theOffsetIsTakenAtTheInstantNotFromTheZone() {
        assertEquals("GMT-4", label("America/New_York", SUMMER));
        assertEquals("GMT+1", label("Europe/London", SUMMER));
    }

    /** Never the abbreviation, however well known it is locally. */
    @Test
    void britishSummerTimeIsNotCalledBst() {
        assertEquals("GMT+1", label("Europe/London", SUMMER));
    }

    /** Half- and quarter-hour zones are real and keep their minutes. */
    @Test
    void partHourZonesKeepTheirMinutes() {
        assertEquals("GMT+5:30", label("Asia/Kolkata", SUMMER));
        assertEquals("GMT+5:45", label("Asia/Kathmandu", SUMMER));
        assertEquals("GMT-3:30", label("America/St_Johns", WINTER));
    }
}
