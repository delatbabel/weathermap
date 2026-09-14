package org.weathermap.render;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColourRampTest {

    private static float brightness(Color c) {
        return Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null)[2];
    }

    private static float saturation(Color c) {
        return Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null)[1];
    }

    /** The stops of the precipitation ramp, wettest last. */
    private static final float[] WET = {0.2f, 1f, 4f, 12f, 30f, 75f};

    /**
     * Heavier rain is darker, the whole way up.
     *
     * <p>The ramp this replaced ran green - teal - blue - purple with every
     * colour between 0.40 and 0.66 brightness. It had a hue progression and
     * almost no lightness one, so it read on the map as a single dark stain
     * that changed shade slightly, and drizzle was told from a downpour by the
     * attribute the eye judges worst.</p>
     */
    @Test
    void heavierRainIsAlwaysDarker() {
        final ColourRamp ramp = ColourRamp.precipitation();
        for (int i = 1; i < WET.length; i++) {
            final float lighter = brightness(ramp.colourFor(WET[i - 1]));
            final float darker = brightness(ramp.colourFor(WET[i]));
            assertTrue(darker < lighter,
                    WET[i] + " mm is not darker than " + WET[i - 1] + " mm: "
                    + darker + " vs " + lighter);
        }
    }

    /** And the span is wide enough to see, not a few percent of grey. */
    @Test
    void theLightnessGradientIsWorthHaving() {
        final ColourRamp ramp = ColourRamp.precipitation();
        final float lightest = brightness(ramp.colourFor(WET[0]));
        final float heaviest = brightness(ramp.colourFor(WET[WET.length - 1]));

        assertTrue(lightest > 0.95f, "the lightest rain should be near white: " + lightest);
        assertTrue(heaviest < 0.5f, "the heaviest should be dark: " + heaviest);
        assertTrue(lightest - heaviest > 0.5f,
                "a gradient of " + (lightest - heaviest) + " is not enough to read");
    }

    /** Lightest is palest, heaviest is most saturated. */
    @Test
    void theLightestRainIsTheLeastSaturated() {
        final ColourRamp ramp = ColourRamp.precipitation();
        final float palest = saturation(ramp.colourFor(WET[0]));

        assertTrue(palest < 0.4f, "drizzle should be a pale wash: " + palest);
        for (int i = 1; i < WET.length; i++) {
            assertTrue(saturation(ramp.colourFor(WET[i])) > palest,
                    WET[i] + " mm should be more saturated than drizzle");
        }
        assertTrue(saturation(ramp.colourFor(75f)) > 0.8f, "the heaviest should be saturated");
    }

    private static float hue(Color c) {
        return Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null)[0];
    }

    /**
     * The hues run yellow, green, blue, indigo, purple as it gets heavier.
     *
     * <p>Checked as a rising hue angle rather than by naming colours: what
     * matters is that the sequence never doubles back, so no two rates can end
     * up looking alike.</p>
     *
     * <p>The check starts at 1 mm. The two lightest stops are both yellow -
     * roughly 53 and 46 degrees - and the paler one is very slightly the
     * greener, so the pair is not monotone by a few degrees within one hue.
     * That wobble is invisible and means nothing; pale yellows simply sit where
     * they sit, and dragging the palest stop toward apricot to satisfy an
     * invariant would be the test choosing the design. They are held apart by
     * lightness and saturation instead, which is what the eye reads here
     * anyway.</p>
     */
    @Test
    void theHueRunsYellowThroughToPurple() {
        final ColourRamp ramp = ColourRamp.precipitation();

        float previous = -1f;
        for (int i = 1; i < WET.length; i++) {
            final float h = hue(ramp.colourFor(WET[i]));
            assertTrue(h > previous, WET[i] + " mm doubles back in hue: " + h);
            previous = h;
        }
        // yellow at the bottom, purple at the top
        assertTrue(hue(ramp.colourFor(0.2f)) > 0.09f && hue(ramp.colourFor(0.2f)) < 0.20f,
                   "drizzle should be yellow: " + hue(ramp.colourFor(0.2f)));
        assertTrue(hue(ramp.colourFor(75f)) > 0.7f,
                   "the heaviest should be purple: " + hue(ramp.colourFor(75f)));
    }

    /** The two yellows are still told apart, by the attributes that carry here. */
    @Test
    void theTwoYellowsAreSeparatedByWeightNotHue() {
        final ColourRamp ramp = ColourRamp.precipitation();
        final Color drizzle = ramp.colourFor(0.2f);
        final Color light = ramp.colourFor(1f);

        assertTrue(saturation(light) - saturation(drizzle) > 0.3f,
                "1 mm should be visibly stronger than 0.2 mm");
        assertTrue(light.getAlpha() > drizzle.getAlpha());
    }

    /** Dry ground shows the base map. */
    @Test
    void nothingIsDrawnWhereNothingIsFalling() {
        assertEquals(0, ColourRamp.precipitation().colourFor(0f).getAlpha());
    }

    /**
     * The ramp is drawn as authored.
     *
     * <p>A uniform brightness scale used to be applied on top of it, which is
     * precisely what flattens a lightness gradient into one dark stain.</p>
     */
    @Test
    void thePrecipitationRampIsNotTonedAgainOnTheWayToTheMap() {
        final var precip = org.weathermap.model.GribCatalog.PRECIPITATION;
        final var grid = new org.weathermap.grib.Grid(
                precip, org.weathermap.model.GribCatalog.LEVEL_SURFACE,
                java.time.Instant.parse("2026-09-14T00:00:00Z"),
                org.weathermap.model.BoundingBox.of(49, -3, 52, 3),
                1, 1, new float[]{1f});

        final ColourRamp styled = FieldStyle.forGrid(grid, false).ramp();

        for (float mm : WET) {
            assertEquals(ColourRamp.precipitation().colourFor(mm), styled.colourFor(mm),
                    "the precipitation ramp is being re-toned at " + mm
                    + " mm; the gradient is the point of it");
        }
    }

    // ---- where the legend puts things --------------------------------------

    /**
     * Rain is read across orders of magnitude, so the legend is logarithmic.
     *
     * <p>Placed linearly, every stop below 12 mm shares a sixth of the bar: the
     * yellow and green half is a sliver at the left end and the legend reads as
     * all indigo and purple, which is the disagreement with the map that
     * started this. The stops are geometric, so the scale that shows them
     * evenly is the log one.</p>
     */
    @Test
    void theRainLegendIsLogarithmic() {
        final ColourRamp ramp = ColourRamp.precipitation();
        assertEquals(ColourRamp.LegendScale.LOGARITHMIC, ramp.legendScale());

        assertEquals(0f, ramp.legendPosition(0f), 1e-4, "dry is the left end");
        assertEquals(1f, ramp.legendPosition(75f), 1e-4, "the wettest is the right end");

        // 4 mm sits in the middle third rather than the first twentieth.
        final float four = ramp.legendPosition(4f);
        assertTrue(four > 0.3f && four < 0.45f, "4 mm is at " + four);
        assertTrue(ramp.legendPosition(4f) > 6 * (4f / 75f),
                "log must spread the low end far wider than linear would");
    }

    /** Temperature is linear in kelvin, and a legend that is not is wrong. */
    @Test
    void theTemperatureLegendStaysLinear() {
        final ColourRamp ramp = ColourRamp.temperatureKelvin();
        assertEquals(ColourRamp.LegendScale.LINEAR, ramp.legendScale());
        assertEquals((273.15f - 233.15f) / (318.15f - 233.15f),
                     ramp.legendPosition(273.15f), 1e-4,
                     "freezing must sit where it actually falls on the scale");
    }

    /**
     * The positions are usable as gradient fractions.
     *
     * <p>{@code LinearGradientPaint} requires them strictly increasing and
     * within 0 to 1, and throws otherwise - which on a chart is not a bad
     * legend but no chart at all.</p>
     */
    @Test
    void everyRampGivesUsableGradientFractions() {
        for (ColourRamp ramp : List.of(
                ColourRamp.precipitation(),
                ColourRamp.temperatureKelvin(),
                ColourRamp.percentGrey("Cloud (%)"),
                ColourRamp.temperatureKelvin().fittedTo(282f, 292f),
                ColourRamp.precipitation().muted(0.5f, 0.3f, 1f))) {

            float previous = -1f;
            for (float stop : ramp.stops()) {
                final float at = ramp.legendPosition(stop);
                assertTrue(at >= 0f && at <= 1f, ramp.name() + " out of range: " + at);
                assertTrue(at > previous, ramp.name() + " not increasing at " + stop);
                previous = at;
            }
            assertEquals(1f, previous, 1e-4, ramp.name() + " should end at the right edge");
        }
    }

    /** A derived ramp is the same quantity, so it keeps the same scale. */
    @Test
    void theScaleSurvivesFittingAndToning() {
        assertEquals(ColourRamp.LegendScale.LOGARITHMIC,
                     ColourRamp.precipitation().muted(0.5f, 0.3f, 1f).legendScale());
        assertEquals(ColourRamp.LegendScale.LOGARITHMIC,
                     ColourRamp.precipitation().fittedTo(0f, 40f).legendScale());
        assertEquals(ColourRamp.LegendScale.LINEAR,
                     ColourRamp.temperatureKelvin().fittedTo(282f, 292f).legendScale());
    }
}
