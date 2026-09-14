package org.weathermap.text;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expectations here are GNU {@code date}'s own output.
 *
 * <p>Every one was produced by running {@code date} against this reference
 * moment and pasted in, rather than reasoned about - three of the rules turned
 * out not to be what they look like, and a test written from the same wrong
 * belief as the code proves nothing.</p>
 *
 * <p>They are fixtures rather than a live comparison because {@code date
 * --date} is a GNU extension: the build has to pass on macOS and Windows, where
 * shelling out to it would fail or, worse, answer differently.</p>
 */
class CaptionTemplateTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    /** Monday 14 September 2026, 22:17:06 +07. */
    private static final ZonedDateTime NOW =
            ZonedDateTime.of(2026, 9, 14, 22, 17, 6, 0, BANGKOK);

    private static String expand(String template) {
        return CaptionTemplate.expand(template, NOW, Locale.ENGLISH);
    }

    private static String at(String expression, String format) {
        return Strftime.format(DateExpression.evaluate(expression, NOW), format, Locale.ENGLISH);
    }

    // ---- the example from the request --------------------------------------

    @Test
    void theExampleFromTheRequest() {
        assertEquals("Tuesday 15 September 2026", expand("${tomorrow:'+%A %e %B %Y'}"));
    }

    @Test
    void aParameterSitsInsideOrdinaryText() {
        assertEquals("Charts for Tuesday 15 September #saigonweather",
                expand("Charts for ${tomorrow:'+%A %e %B'} #saigonweather"));
    }

    @Test
    void severalParametersInOneCaption() {
        assertEquals("2026-09-13 to 2026-09-15",
                expand("${yesterday} to ${tomorrow}"));
    }

    // ---- quoting -----------------------------------------------------------

    /** Either half, either kind of quote, or none. */
    @Test
    void bothHalvesMayBeQuotedEitherWay() {
        final String expected = "Tuesday 15 September 2026";
        assertEquals(expected, expand("${tomorrow:'+%A %e %B %Y'}"));
        assertEquals(expected, expand("${tomorrow:\"+%A %e %B %Y\"}"));
        assertEquals(expected, expand("${'tomorrow':'+%A %e %B %Y'}"));
        assertEquals(expected, expand("${\"tomorrow\":\"+%A %e %B %Y\"}"));
        assertEquals(expected, expand("${tomorrow:%A %e %B %Y}"));
    }

    /**
     * A colon belongs to whichever half it was written in.
     *
     * <p>Neither the first colon nor the last divides this: the date carries a
     * time and so does the format. The format is the half beginning with
     * {@code +}, and quoting the date settles it outright.</p>
     */
    @Test
    void aColonOnEitherSideStaysOnItsOwnSide() {
        assertEquals("14:30", expand("${'2026-09-15 14:30':'+%H:%M'}"));
        assertEquals("14:30", expand("${2026-09-15 14:30:'+%H:%M'}"));
        assertEquals("2026-09-15 14:30", expand("${\"2026-09-15 14:30\":\"+%F %H:%M\"}"));
    }

    @Test
    void aDateWithNoFormatGivesTheIsoDate() {
        assertEquals("2026-09-15", expand("${tomorrow}"));
    }

    @Test
    void aDoubledDollarIsALiteral() {
        assertEquals("${tomorrow} costs $5", expand("$${tomorrow} costs $5"));
    }

    @Test
    void textWithNothingToExpandIsUntouched() {
        assertEquals("Just a caption #weather", expand("Just a caption #weather"));
        assertFalse(CaptionTemplate.hasParameters("Just a caption"));
    }

    /** An unclosed parameter is text; it is not worth guessing where it ended. */
    @Test
    void anUnclosedParameterIsLeftAlone() {
        assertEquals("look ${tomorrow", expand("look ${tomorrow"));
    }

    // ---- relative dates keep the time of day -------------------------------

    /** Verified against GNU date: a relative offset does not go to midnight. */
    @Test
    void aRelativeOffsetKeepsTheTimeOfDay() {
        assertEquals("2026-09-15 22:17:06", at("tomorrow", "%F %T"));
        assertEquals("2026-09-13 22:17:06", at("yesterday", "%F %T"));
        assertEquals("2026-09-21 22:17:06", at("next week", "%F %T"));
        assertEquals("2026-09-15 04:17:06", at("+6 hours", "%F %T"));
        assertEquals("2026-08-31 22:17:06", at("-2 weeks", "%F %T"));
        assertEquals("2026-09-11 22:17:06", at("3 days ago", "%F %T"));
        assertEquals("2026-08-14 22:17:06", at("last month", "%F %T"));
        assertEquals("2024-09-14 22:17:06", at("2 years ago", "%F %T"));
    }

    /** And naming a day, or a date, does go to midnight. */
    @Test
    void namingADayOrADateMeansMidnight() {
        assertEquals("2026-09-18 00:00:00", at("friday", "%F %T"));
        assertEquals("2026-09-05 00:00:00", at("2026-09-05", "%F %T"));
    }

    /**
     * "next monday" on a Monday is a week away; a bare "monday" is today.
     *
     * <p>The one rule most likely to be got wrong, and the one most likely to
     * put the wrong date on a post: "next" means strictly after today, a bare
     * day or "this" means on or after it.</p>
     */
    @Test
    void nextSkipsTodayAndABareDayDoesNot() {
        assertEquals("2026-09-14", at("monday", "%F"), "today is Monday");
        assertEquals("2026-09-14", at("this monday", "%F"));
        assertEquals("2026-09-21", at("next monday", "%F"));
        assertEquals("2026-09-15", at("next tuesday", "%F"));
        assertEquals("2026-09-11", at("last friday", "%F"));
        assertEquals("2026-09-20", at("sunday", "%F"));
    }

    @Test
    void anExplicitTimeBeatsBoth() {
        assertEquals("2026-09-15 09:00:00", at("tomorrow 09:00", "%F %T"));
        assertEquals("2026-09-18 06:00:00", at("next friday 06:00", "%F %T"));
    }

    @Test
    void secondsSinceTheEpoch() {
        assertEquals("2026-09-10 07:26:40", at("@1789000000", "%F %T"));
    }

    // ---- formatting --------------------------------------------------------

    /** Padding is the part that gets got wrong, so it follows date(1) exactly. */
    @Test
    void theThreeKindsOfDayPadding() {
        assertEquals("05", at("2026-09-05", "%d"), "%d is zero-padded");
        assertEquals(" 5", at("2026-09-05", "%e"), "%e is space-padded");
        assertEquals("5", at("2026-09-05", "%-d"), "%-d is not padded");
        assertEquals(" 5", at("2026-09-05", "%_d"));
        assertEquals("Saturday  5 September 2026", at("2026-09-05", "%A %e %B %Y"),
                "the double space is date(1)'s own");
    }

    @Test
    void theCommonConversions() {
        assertEquals("2026-09-15", at("2026-09-15 14:05:09", "%F"));
        assertEquals("14:05:09", at("2026-09-15 14:05:09", "%T"));
        assertEquals("Tue 15 Sep 26", at("2026-09-15 14:05:09", "%a %d %b %y"));
        assertEquals("02:05 PM", at("2026-09-15 14:05:09", "%I:%M %p"));
        assertEquals("258", at("2026-09-15 14:05:09", "%j"));
        assertEquals("15/9/2026", at("2026-09-15 14:05:09", "%-d/%-m/%Y"));
        assertEquals("September 15, 2026", at("2026-09-15 14:05:09", "%B %-d, %Y"));
        assertEquals("+0700", at("2026-09-15 14:05:09", "%z"));
        assertEquals("%", at("2026-09-15", "%%"));
    }

    /** The format may arrive exactly as it would be typed at a shell. */
    @Test
    void aFormatKeepsItsShellPlusAndQuotes() {
        assertEquals("2026-09-15", Strftime.format(
                DateExpression.evaluate("2026-09-15", NOW), "'+%F'", Locale.ENGLISH));
        assertEquals("2026-09-15", Strftime.format(
                DateExpression.evaluate("2026-09-15", NOW), "%F", Locale.ENGLISH));
    }

    // ---- being wrong out loud ----------------------------------------------

    /**
     * A caption is published before anyone reads it.
     *
     * <p>So an expression that is not understood is reported, never guessed at
     * and never quietly turned into today.</p>
     */
    @Test
    void anUnknownDateIsReportedRatherThanGuessed() {
        final List<CaptionTemplate.Problem> problems =
                CaptionTemplate.problems("on ${nextish tuesday:'+%F'}", NOW, Locale.ENGLISH);

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).reason().contains("nextish"), problems.get(0).toString());
        // and the text is left exactly as written, not replaced with a wrong date
        assertEquals("on ${nextish tuesday:'+%F'}", expand("on ${nextish tuesday:'+%F'}"));
    }

    @Test
    void anUnknownConversionIsNamedAlongWithTheOnesThatWork() {
        final var problems = CaptionTemplate.problems("${today:'+%Q'}", NOW, Locale.ENGLISH);

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).reason().contains("%Q"), problems.get(0).toString());
        assertTrue(problems.get(0).reason().contains("%A"), "it should list what does work");
    }

    @Test
    void aNumberWithNoUnitSaysSo() {
        assertThrows(DateExpression.UnsupportedException.class,
                () -> DateExpression.evaluate("3", NOW));
    }

    @Test
    void aGoodTemplateHasNoProblems() {
        assertTrue(CaptionTemplate.problems(
                "Charts for ${tomorrow:'+%A %e %B'}", NOW, Locale.ENGLISH).isEmpty());
    }

    // ---- the zone is the chart's -------------------------------------------

    /**
     * The caption and the labels on the images have to agree about the day.
     *
     * <p>At 22:17 in Bangkok it is still the 14th in London and already the
     * 15th in Auckland, so a caption expanded in the machine's zone can name a
     * different day from the chart beside it.</p>
     */
    @Test
    void theDateIsWhateverDayItIsInTheChartsZone() {
        final ZonedDateTime sameMoment = NOW.withZoneSameInstant(ZoneId.of("Europe/London"));

        assertEquals("2026-09-14", CaptionTemplate.expand("${today}", NOW, Locale.ENGLISH));
        assertEquals("2026-09-14",
                CaptionTemplate.expand("${today}", sameMoment, Locale.ENGLISH));
        assertEquals("16:17", Strftime.format(sameMoment, "%H:%M", Locale.ENGLISH),
                "the same instant, four hours earlier on the clock");
    }
}
