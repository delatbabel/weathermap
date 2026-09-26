package org.weathermap.osm;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The named place closest to a point, out of whatever map data is in hand.
 *
 * <h2>What it is for</h2>
 *
 * <p>Answering "where did I just click?" in words. A latitude and a longitude
 * are exact and say nothing: 10.35, 107.08 could be a harbour, a headland or
 * forty kilometres out to sea, and the difference decides whether a tide read
 * there means anything. A name and a distance make it checkable before a
 * request is spent on it.</p>
 *
 * <h2>What "nearest" means here</h2>
 *
 * <p>Nearest of the <em>label points</em> in the features already loaded, which
 * is a weaker claim than nearest place on earth and is why the distance is
 * always reported with the name. Zoomed in, those features are real OSM places
 * and the answer is good to a village; zoomed out they are the bundled Natural
 * Earth gazetteer and the nearest city may be a long way off. Saying "180 km
 * away" is the honest version of both.</p>
 *
 * <p>Only single-point kinds are considered - {@link FeatureKind#PLACE},
 * {@link FeatureKind#MARINE}, {@link FeatureKind#COUNTRY}. A coastline is a
 * line, and its nearest vertex is an artefact of where the surveyors put nodes
 * rather than a place anyone could name.</p>
 */
public final class NearestPlace {

    /** The kinds worth naming a point by, most specific first. */
    public static final Set<FeatureKind> NAMEABLE =
            Set.of(FeatureKind.PLACE, FeatureKind.MARINE, FeatureKind.COUNTRY);

    /** Mean Earth radius, in kilometres. */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private NearestPlace() { }

    /** A named feature and how far it is from the point that was asked about. */
    public record Match(Feature feature, double distanceKm) {

        public String name() { return feature.name(); }

        public FeatureKind kind() { return feature.kind(); }

        public double lat() { return feature.points().get(0)[0]; }

        public double lon() { return feature.points().get(0)[1]; }

        /** What to show a user: the name, what sort of thing it is, and how far. */
        public String describe() {
            final StringBuilder said = new StringBuilder(String.valueOf(name()));
            final String what = descriptor();
            if (what != null) said.append(" (").append(what).append(')');
            return said.append(" — ").append(distance()).append(" away").toString();
        }

        /** A rounded distance, in the unit that suits it. */
        public String distance() {
            if (distanceKm < 1) {
                return String.format(Locale.ROOT, "%.0f m", distanceKm * 1000);
            }
            if (distanceKm < 10) return String.format(Locale.ROOT, "%.1f km", distanceKm);
            return String.format(Locale.ROOT, "%.0f km", distanceKm);
        }

        private String descriptor() {
            return switch (feature.kind()) {
                case PLACE -> feature.placeType();
                case MARINE -> "sea";
                case COUNTRY -> "country";
                default -> null;
            };
        }
    }

    /**
     * The nearest named feature to a point.
     *
     * @param kinds which kinds to consider; see {@link #NAMEABLE}
     * @return the match, or null when nothing in {@code features} has a name
     */
    public static Match near(double lat, double lon, List<Feature> features,
                             Set<FeatureKind> kinds) {
        Feature best = null;
        double bestKm = Double.POSITIVE_INFINITY;

        for (Feature f : features) {
            if (!kinds.contains(f.kind())) continue;
            if (f.name() == null || f.name().isBlank()) continue;
            if (f.points().isEmpty()) continue;

            final double[] at = f.points().get(0);
            final double km = distanceKm(lat, lon, at[0], at[1]);
            if (km < bestKm) {
                bestKm = km;
                best = f;
            }
        }
        return best == null ? null : new Match(best, bestKm);
    }

    /** The nearest populated place, ignoring seas and countries. */
    public static Match nearestTown(double lat, double lon, List<Feature> features) {
        return near(lat, lon, features, Set.of(FeatureKind.PLACE));
    }

    /**
     * Great-circle distance in kilometres.
     *
     * <p>Haversine, which needs no antimeridian case of its own: it works on
     * the sine and cosine of the difference in longitude, and those do not care
     * that 179 and -179 are two degrees apart rather than three hundred and
     * fifty-eight.</p>
     */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        final double dLat = Math.toRadians(lat2 - lat1);
        final double dLon = Math.toRadians(lon2 - lon1);
        final double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
