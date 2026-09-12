package org.weathermap.render;

import org.weathermap.grib.Grid;
import org.weathermap.model.GribCatalog;
import org.weathermap.model.GribVariable;

/**
 * How one scalar field is drawn: which ramp, and how strongly.
 *
 * <p>A per-field decision rather than a global one, because the right answer
 * genuinely differs. On a chart whose subject is the wind, temperature wants to
 * be a pale wash that can be read past, while precipitation wants enough weight
 * to be seen as a thing in its own right. One opacity setting for both makes one
 * of them wrong.</p>
 *
 * @param ramp    the colours, already fitted and toned
 * @param opacity 0 to 1, before {@link org.weathermap.model.RenderSpec#gribOpacity()}
 *                scales it
 */
public record FieldStyle(ColourRamp ramp, float opacity) {

    /**
     * The house style for a decoded field.
     *
     * <p>The numbers are judgement, arrived at by looking at the output, and are
     * the first thing to change if the balance looks wrong.</p>
     */
    public static FieldStyle forGrid(Grid grid, boolean autoScale) {
        final GribVariable variable = grid.variable();
        ColourRamp ramp = ColourRamp.forVariable(variable);

        // Fitting must happen before toning, so the tone applies to the colours
        // actually used rather than to the ends of a ramp nothing reaches.
        if (autoScale && !ramp.isAbsolute()) {
            final float[] range = grid.range();
            ramp = ramp.fittedTo(range[0], range[1]);
        }

        return switch (variable.code()) {
            // Context, not subject: washed out badly enough to read straight
            // past, while keeping the cold-to-warm hue order.
            case "TMP", "DPT" -> new FieldStyle(ramp.muted(0.40f, 0.45f, 1f), 0.32f);

            // Weight on the map. Darker and more opaque than temperature, but
            // still transparent where nothing is falling.
            case "APCP" -> new FieldStyle(ramp.darkened(0.85f, 1.15f, 1f), 0.62f);

            // Cloud reads as a grey veil; humidity likewise.
            case "TCDC", "RH" -> new FieldStyle(ramp.muted(0.7f, 0.2f, 1f), 0.38f);

            default -> new FieldStyle(ramp, 0.45f);
        };
    }

    /** True for a field that should not be drawn as a wash at all. */
    public static boolean isVectorComponent(GribVariable variable) {
        return GribCatalog.WIND_U.code().equals(variable.code())
                || GribCatalog.WIND_V.code().equals(variable.code());
    }
}
