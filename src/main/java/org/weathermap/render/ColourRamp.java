package org.weathermap.render;

import java.awt.Color;

/**
 * Maps a field value to a colour.
 *
 * <p>Ramps are per-variable because the sensible breakpoints are: temperature
 * wants a diverging ramp centred on freezing, precipitation a sequential one
 * starting at transparent, cloud cover a greyscale. A single rainbow ramp for
 * everything is the usual mistake - it invents boundaries the data does not
 * have and is unreadable to the colour-blind.</p>
 *
 * <p>Ramps interpolate in sRGB, which is not perceptually uniform. That is a
 * deliberate simplification for now; the fix is to interpolate in Oklab or CIE
 * Lab, and the seam for it is {@link #interpolate}.</p>
 */
public final class ColourRamp {

    private final float[] stops;
    private final Color[] colours;
    private final String name;

    /**
     * @param name    for the legend
     * @param stops   ascending positions in the ramp's own value units
     * @param colours one per stop
     */
    public ColourRamp(String name, float[] stops, Color[] colours) {
        if (stops.length != colours.length || stops.length < 2) {
            throw new IllegalArgumentException("need at least two matching stops and colours");
        }
        this.name = name;
        this.stops = stops.clone();
        this.colours = colours.clone();
    }

    public String name() { return name; }

    public float min() { return stops[0]; }

    public float max() { return stops[stops.length - 1]; }

    /** @return the colour for a value, clamped to the ends of the ramp. */
    public Color colourFor(float value) {
        if (Float.isNaN(value)) return new Color(0, 0, 0, 0);
        if (value <= stops[0]) return colours[0];
        if (value >= stops[stops.length - 1]) return colours[colours.length - 1];
        for (int i = 1; i < stops.length; i++) {
            if (value <= stops[i]) {
                final float t = (value - stops[i - 1]) / (stops[i] - stops[i - 1]);
                return interpolate(colours[i - 1], colours[i], t);
            }
        }
        return colours[colours.length - 1];
    }

    /**
     * The same colour sequence stretched onto a new range.
     *
     * <p>Needed because a fixed physical scale and a readable map are often at
     * odds. The temperature ramp spans 233-318 K so that freezing always sits at
     * the same colour, which is the right choice when comparing maps - but a
     * September field over Britain occupies 282-292 K, ten kelvin out of
     * eighty-five, and renders as a single flat orange.</p>
     *
     * <p>Fitting to the data makes the structure visible at the cost of
     * comparability between maps: the same colour means different values on
     * different days. {@link org.weathermap.model.RenderSpec#autoScaleRamp()}
     * chooses, and the legend always shows the range actually used, so a fitted
     * map never misleads about what it is showing.</p>
     *
     * @return a ramp over {@code [min, max]}, or {@code this} when the range is
     *         degenerate
     */
    public ColourRamp fittedTo(float min, float max) {
        if (!(max > min) || Float.isNaN(min) || Float.isNaN(max)) return this;
        final float span = stops[stops.length - 1] - stops[0];
        if (span <= 0) return this;

        final float[] fitted = new float[stops.length];
        for (int i = 0; i < stops.length; i++) {
            final float fraction = (stops[i] - stops[0]) / span;
            fitted[i] = min + (max - min) * fraction;
        }
        return new ColourRamp(name, fitted, colours);
    }

    /**
     * The same ramp with its colours pulled toward a pale wash.
     *
     * <p>For a field that is context rather than the subject. Temperature over
     * a marine chart is exactly that: useful to have, ruinous if it competes
     * with the wind barbs and the coastline for attention. Saturation is scaled
     * down and lightness lifted toward white, which keeps the hue ordering - so
     * the ramp still reads cold-to-warm - while removing its weight.</p>
     *
     * <p>Worked in HSB rather than RGB because scaling RGB channels toward white
     * shifts the hue, and a temperature ramp whose "cold" end has drifted toward
     * cyan is worse than no ramp.</p>
     *
     * @param saturationScale below 1 desaturates
     * @param lightnessLift   0 to 1, the fraction of the way to full brightness
     * @param alphaScale      below 1 makes it more transparent
     */
    public ColourRamp muted(float saturationScale, float lightnessLift, float alphaScale) {
        final Color[] out = new Color[colours.length];
        for (int i = 0; i < colours.length; i++) {
            final Color c = colours[i];
            final float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
            final float saturation = clamp01(hsb[1] * saturationScale);
            final float brightness = clamp01(hsb[2] + (1f - hsb[2]) * lightnessLift);
            final Color muted = new Color(Color.HSBtoRGB(hsb[0], saturation, brightness));
            out[i] = new Color(muted.getRed(), muted.getGreen(), muted.getBlue(),
                               Math.round(clamp01(c.getAlpha() / 255f * alphaScale) * 255));
        }
        return new ColourRamp(name, stops, out);
    }

    /**
     * The same ramp with its colours taken toward the dark end.
     *
     * <p>The opposite treatment, for a field that should read as weight on the
     * map - rain being the obvious one. Brightness is scaled down and saturation
     * pushed up, so heavy precipitation reads as a dark stain rather than a
     * pastel smear.</p>
     */
    public ColourRamp darkened(float brightnessScale, float saturationScale, float alphaScale) {
        final Color[] out = new Color[colours.length];
        for (int i = 0; i < colours.length; i++) {
            final Color c = colours[i];
            final float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
            final Color darker = new Color(Color.HSBtoRGB(
                    hsb[0], clamp01(hsb[1] * saturationScale), clamp01(hsb[2] * brightnessScale)));
            out[i] = new Color(darker.getRed(), darker.getGreen(), darker.getBlue(),
                               Math.round(clamp01(c.getAlpha() / 255f * alphaScale) * 255));
        }
        return new ColourRamp(name, stops, out);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static Color interpolate(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    // ---- the built-in ramps ---------------------------------------------

    /** Diverging about 0 &deg;C (273.15 K), blue cold to red warm. */
    public static ColourRamp temperatureKelvin() {
        return new ColourRamp("Temperature (K)",
                new float[]{233.15f, 253.15f, 273.15f, 288.15f, 303.15f, 318.15f},
                new Color[]{
                        new Color(49, 54, 149),
                        new Color(116, 173, 209),
                        new Color(255, 255, 191),
                        new Color(253, 174, 97),
                        new Color(215, 48, 39),
                        new Color(120, 10, 20)});
    }

    /**
     * Sequential and deliberately dark, transparent at zero so dry ground shows
     * the base map.
     *
     * <p>Weighted toward the low end - 0.5, 2 and 10 mm are the steps that
     * matter to anyone reading a forecast, and a linear ramp to 75 would render
     * all of them as the same faint green.</p>
     */
    public static ColourRamp precipitation() {
        return new ColourRamp("Precipitation (kg/m²)",
                new float[]{0f, 0.2f, 1f, 4f, 12f, 30f, 75f},
                new Color[]{
                        new Color(255, 255, 255, 0),
                        new Color(120, 170, 135, 120),
                        new Color(56, 132, 92, 175),
                        new Color(26, 100, 84, 205),
                        new Color(22, 68, 96, 225),
                        new Color(58, 34, 94, 238),
                        new Color(74, 12, 52, 246)});
    }

    /** Greyscale, transparent when clear. */
    public static ColourRamp percentGrey(String label) {
        return new ColourRamp(label,
                new float[]{0f, 100f},
                new Color[]{new Color(255, 255, 255, 0), new Color(245, 245, 245, 230)});
    }

    /**
     * Whether this ramp's stops carry physical meaning that rescaling would
     * destroy.
     *
     * <p>True for precipitation, where zero must stay transparent so dry ground
     * shows the base map, and for percentages, which are already 0-100. False
     * for temperature and anything uncatalogued, where the absolute values are
     * arbitrary relative to the picture.</p>
     */
    public boolean isAbsolute() {
        return stops[0] == 0f;
    }

    /** Picks a ramp for a variable. */
    public static ColourRamp forVariable(org.weathermap.model.GribVariable variable) {
        return switch (variable.code()) {
            case "TMP", "DPT" -> temperatureKelvin();
            case "APCP" -> precipitation();
            case "TCDC", "RH" -> percentGrey(variable.displayName() + " (%)");
            default -> percentGrey(variable.displayName());
        };
    }
}
