package org.weathermap.model;

/**
 * One meteorological field, named the way NOMADS names it.
 *
 * @param code        the NOMADS parameter, used as {@code var_<code>=on} - e.g. {@code TMP}
 * @param displayName what the UI calls it
 * @param unit        the unit the raw values are in, for the legend
 * @param style       how {@link org.weathermap.render.GribLayer} should draw it
 */
public record GribVariable(String code, String displayName, String unit, RenderStyle style) {

    /** How a field is drawn over the base map. */
    public enum RenderStyle {
        /** A translucent colour ramp - the default for scalar fields. */
        FILLED_CONTOUR,
        /** Isolines with labels, for pressure and heights. */
        CONTOUR_LINES,
        /** Barbs or arrows; needs a paired u/v variable. */
        VECTOR,
        /** Ramp plus isolines. */
        FILLED_AND_LINES
    }

    /** The {@code var_<code>=on} query parameter. */
    public String queryParam() {
        return "var_" + code + "=on";
    }

    @Override
    public String toString() {
        return displayName + " (" + unit + ")";
    }
}
