package org.weathermap.model;

import java.util.List;

/**
 * The variables and levels the UI offers, and the defaults.
 *
 * <p>Deliberately a short, hand-picked list rather than everything NOMADS
 * publishes. The filter forms carry hundreds of parameters, most of which mean
 * nothing without a meteorology background and several of which do not exist in
 * every model. A curated set keeps the UI usable; anything missing can be added
 * here in one line, and the CLI accepts raw codes for the cases this list does
 * not cover.</p>
 *
 * <p><b>Not validated against the model.</b> Whether a given variable/level pair
 * exists for a given model is only discoverable from that model's filter form,
 * so an unavailable combination fails at download time with an empty result
 * rather than being greyed out in the UI. See README.md.</p>
 */
public final class GribCatalog {

    private GribCatalog() { }

    // ---- variables ------------------------------------------------------

    public static final GribVariable TEMPERATURE =
            new GribVariable("TMP", "Temperature", "K", GribVariable.RenderStyle.FILLED_CONTOUR);
    public static final GribVariable DEWPOINT =
            new GribVariable("DPT", "Dew point", "K", GribVariable.RenderStyle.FILLED_CONTOUR);
    public static final GribVariable RELATIVE_HUMIDITY =
            new GribVariable("RH", "Relative humidity", "%", GribVariable.RenderStyle.FILLED_CONTOUR);
    public static final GribVariable PRESSURE_MSL =
            new GribVariable("PRMSL", "Pressure (MSL)", "Pa", GribVariable.RenderStyle.CONTOUR_LINES);
    public static final GribVariable PRECIPITATION =
            new GribVariable("APCP", "Precipitation", "kg/m²", GribVariable.RenderStyle.FILLED_CONTOUR);
    public static final GribVariable TOTAL_CLOUD =
            new GribVariable("TCDC", "Total cloud cover", "%", GribVariable.RenderStyle.FILLED_CONTOUR);
    /**
     * Wind, as one selectable thing.
     *
     * <p>Fetches {@code UGRD} and {@code VGRD} together, because a barb needs
     * both and selecting one alone would download data that cannot be drawn.
     * The decoder still returns them as two separate {@link org.weathermap.grib.Grid}s;
     * {@link org.weathermap.render.Compositor} pairs them again.</p>
     */
    public static final GribVariable WIND =
            new GribVariable("UGRD", "Wind", "kt", GribVariable.RenderStyle.VECTOR, "VGRD");

    /** The eastward component on its own, as the decoder reports it. */
    public static final GribVariable WIND_U =
            new GribVariable("UGRD", "Wind (u component)", "m/s", GribVariable.RenderStyle.VECTOR);

    /** The northward component on its own, as the decoder reports it. */
    public static final GribVariable WIND_V =
            new GribVariable("VGRD", "Wind (v component)", "m/s", GribVariable.RenderStyle.VECTOR);
    public static final GribVariable GEOPOTENTIAL_HEIGHT =
            new GribVariable("HGT", "Geopotential height", "gpm", GribVariable.RenderStyle.CONTOUR_LINES);

    /**
     * What the UI offers, wind first because it is the field these maps exist
     * for.
     */
    public static final List<GribVariable> VARIABLES = List.of(
            WIND, TEMPERATURE, PRECIPITATION, DEWPOINT, RELATIVE_HUMIDITY,
            PRESSURE_MSL, TOTAL_CLOUD, GEOPOTENTIAL_HEIGHT);

    // ---- levels ---------------------------------------------------------

    public static final GribLevel LEVEL_SURFACE = new GribLevel("surface", "Surface");
    public static final GribLevel LEVEL_MSL = new GribLevel("mean_sea_level", "Mean sea level");
    public static final GribLevel LEVEL_2M = new GribLevel("2_m_above_ground", "2 m above ground");
    public static final GribLevel LEVEL_10M = new GribLevel("10_m_above_ground", "10 m above ground");
    public static final GribLevel LEVEL_850MB = new GribLevel("850_mb", "850 mb");
    public static final GribLevel LEVEL_700MB = new GribLevel("700_mb", "700 mb");
    public static final GribLevel LEVEL_500MB = new GribLevel("500_mb", "500 mb");
    public static final GribLevel LEVEL_300MB = new GribLevel("300_mb", "300 mb");
    public static final GribLevel LEVEL_ENTIRE_ATMOSPHERE =
            new GribLevel("entire_atmosphere", "Entire atmosphere");

    public static final List<GribLevel> LEVELS = List.of(
            LEVEL_SURFACE, LEVEL_MSL, LEVEL_2M, LEVEL_10M,
            LEVEL_850MB, LEVEL_700MB, LEVEL_500MB, LEVEL_300MB, LEVEL_ENTIRE_ATMOSPHERE);

    /** @return the catalogued variable with this NOMADS code, or a bare one if unknown. */
    public static GribVariable variable(String code) {
        // UGRD resolves to WIND, so asking for it on the command line fetches
        // the partner too; VGRD is listed after it so the decoder can still
        // name a lone northward grid correctly.
        for (GribVariable v : VARIABLES) {
            if (v.code().equalsIgnoreCase(code)) return v;
        }
        if (WIND_V.code().equalsIgnoreCase(code)) return WIND_V;
        return new GribVariable(code.toUpperCase(java.util.Locale.ROOT), code, "",
                                GribVariable.RenderStyle.FILLED_CONTOUR);
    }

    /** @return the catalogued level with this NOMADS code, or a bare one if unknown. */
    public static GribLevel level(String code) {
        for (GribLevel l : LEVELS) {
            if (l.code().equalsIgnoreCase(code)) return l;
        }
        return new GribLevel(code, code.replace('_', ' '));
    }
}
