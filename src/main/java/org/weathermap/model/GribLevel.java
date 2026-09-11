package org.weathermap.model;

/**
 * A vertical level, named the way NOMADS names it.
 *
 * <p>The {@code code} is the CGI parameter with {@code lev_} stripped off and
 * spaces turned into underscores, exactly as it appears in the filter form -
 * {@code surface}, {@code 2_m_above_ground}, {@code 500_mb}. It is passed
 * through verbatim rather than reconstructed, because the naming is not
 * systematic enough to generate.</p>
 *
 * @param code        the NOMADS level, used as {@code lev_<code>=on}
 * @param displayName what the UI calls it
 */
public record GribLevel(String code, String displayName) {

    /** The {@code lev_<code>=on} query parameter. */
    public String queryParam() {
        return "lev_" + code + "=on";
    }

    @Override
    public String toString() { return displayName; }
}
