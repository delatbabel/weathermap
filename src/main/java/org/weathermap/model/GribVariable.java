package org.weathermap.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One meteorological field, named the way NOMADS names it.
 *
 * <p>Usually one NOMADS parameter, but not always: wind is a vector, and a
 * barb needs both the eastward and northward components. Rather than make the
 * user select {@code UGRD} and {@code VGRD} separately and silently draw
 * nothing when they forget one, a variable may name a {@code pairedCode} that
 * is requested alongside it.</p>
 *
 * @param code        the NOMADS parameter, used as {@code var_<code>=on} - e.g. {@code TMP}
 * @param displayName what the UI calls it
 * @param unit        the unit the raw values are in, for the legend
 * @param style       how the field should be drawn
 * @param pairedCode  a second parameter fetched with it, or {@code null}
 */
public record GribVariable(String code, String displayName, String unit,
                           RenderStyle style, String pairedCode) {

    public GribVariable(String code, String displayName, String unit, RenderStyle style) {
        this(code, displayName, unit, style, null);
    }

    /** How a field is drawn over the base map. */
    public enum RenderStyle {
        /** A translucent colour ramp - the default for scalar fields. */
        FILLED_CONTOUR,
        /** Isolines with labels, for pressure and heights. */
        CONTOUR_LINES,
        /** Wind barbs; needs a paired u/v component. */
        VECTOR,
        /** Ramp plus isolines. */
        FILLED_AND_LINES
    }

    /** The {@code var_<code>=on} parameters this variable needs. */
    public List<String> queryParams() {
        final List<String> out = new ArrayList<>(2);
        out.add("var_" + code + "=on");
        if (pairedCode != null) out.add("var_" + pairedCode + "=on");
        return out;
    }

    /** @deprecated use {@link #queryParams()}, which handles paired components. */
    @Deprecated
    public String queryParam() {
        return "var_" + code + "=on";
    }

    @Override
    public String toString() {
        return unit.isEmpty() ? displayName : displayName + " (" + unit + ")";
    }
}
