package org.weathermap.tide;

import java.util.Locale;

/**
 * Where a tide is being read, and what to call it.
 *
 * <p>A name and a position travel together from the moment the user picks a
 * spot on the map: the name is what they recognised it by and what belongs at
 * the top of the chart, the position is what gets sent. Keeping them apart
 * meant the chart was headed with six decimal places of latitude, which is
 * precise and says nothing.</p>
 *
 * <p>The name is what the <em>map data</em> called the nearest place, not what
 * Storm Glass calls its gauge. The two are different claims and the chart
 * shows both - "Vũng Tàu" as the heading, and underneath it which station
 * actually answered and how far away it is.</p>
 *
 * @param name the nearest named place, or null when nothing was near enough
 *             to be worth printing
 */
public record TidePoint(String name, double lat, double lon) {

    /** The position written the way a chart writes one. */
    public String coordinates() {
        return String.format(Locale.ROOT, "%.4f°%s  %.4f°%s",
                Math.abs(lat), lat >= 0 ? "N" : "S",
                Math.abs(lon), lon >= 0 ? "E" : "W");
    }

    /** The name if there is one, otherwise the coordinates. */
    public String title() {
        return (name == null || name.isBlank()) ? coordinates() : name;
    }

    /** True when {@link #title()} would only repeat the coordinates. */
    public boolean isNamed() { return name != null && !name.isBlank(); }
}
