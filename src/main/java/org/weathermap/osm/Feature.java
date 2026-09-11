package org.weathermap.osm;

import java.util.List;
import java.util.Map;

/**
 * One drawable thing pulled out of OSM: a coastline segment, a boundary, or a
 * populated place.
 *
 * <p>Deliberately thinner than the OSM data model. OSM has nodes, ways and
 * relations with arbitrary tags and shared geometry; a renderer needs a list of
 * points, a kind, and the handful of tags that affect how it is drawn. The
 * flattening happens in {@link OsmXmlParser} so nothing downstream has to know
 * what a relation member is.</p>
 *
 * @param kind   what the renderer should treat this as
 * @param points the geometry in {@code {lat, lon}} pairs; one point for a place,
 *               many for a line
 * @param tags   the OSM tags that survived the flattening
 */
public record Feature(FeatureKind kind, List<double[]> points, Map<String, String> tags) {

    public Feature {
        points = List.copyOf(points);
        tags = Map.copyOf(tags);
    }

    /** The {@code name} tag, or {@code null}. */
    public String name() { return tags.get("name"); }

    /**
     * The population, for deciding which labels survive when they collide.
     *
     * @return the parsed {@code population} tag, or -1 when absent or unparseable
     *         (OSM population values are free text and include things like
     *         "approx 5000")
     */
    public long population() {
        final String s = tags.get("population");
        if (s == null) return -1;
        try {
            return Long.parseLong(s.trim());
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The {@code place} tag - {@code city}, {@code town}, {@code village}. */
    public String placeType() { return tags.get("place"); }

    /** The {@code admin_level} tag as an int, or -1. 2 is a national boundary. */
    public int adminLevel() {
        final String s = tags.get("admin_level");
        if (s == null) return -1;
        try {
            return Integer.parseInt(s.trim());
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    public boolean isClosed() {
        if (points.size() < 3) return false;
        final double[] first = points.get(0);
        final double[] last = points.get(points.size() - 1);
        return first[0] == last[0] && first[1] == last[1];
    }
}
