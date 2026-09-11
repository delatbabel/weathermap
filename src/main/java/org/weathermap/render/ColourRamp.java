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

    /** Sequential, transparent at zero so dry areas show the base map. */
    public static ColourRamp precipitation() {
        return new ColourRamp("Precipitation (kg/m²)",
                new float[]{0f, 0.5f, 2f, 10f, 25f, 75f},
                new Color[]{
                        new Color(255, 255, 255, 0),
                        new Color(199, 233, 192, 180),
                        new Color(116, 196, 118, 200),
                        new Color(35, 139, 69, 220),
                        new Color(0, 109, 44, 235),
                        new Color(120, 0, 120, 245)});
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
