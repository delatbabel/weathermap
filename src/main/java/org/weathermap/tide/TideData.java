package org.weathermap.tide;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A tide prediction for one point: the curve, the turning points, and which
 * station it came from.
 *
 * <p>The boundary between "Storm Glass" and "chart", the way {@code Grid} is
 * the boundary between "GRIB" and "picture". {@link TideChart} draws this and
 * knows nothing about HTTP or JSON, so a second source - a national tide
 * service, a file a colleague sent - would change nothing downstream.</p>
 *
 * <h2>Heights are relative, not depths</h2>
 *
 * <p>Every height is metres relative to {@link #datum}, and the datum is
 * carried rather than assumed because it decides what zero means. Against
 * {@code MSL} a low tide is a negative number; against {@code LAT} almost
 * nothing is. Storm Glass is explicit that these are never water depth, which
 * is why the datum is printed on the chart instead of being quietly dropped.</p>
 *
 * @param retrievedAt when this answer was fetched from the service, which is
 *                    not now when it came out of the cache
 * @param cached      true when it was served from the cache rather than fetched
 */
public record TideData(Station station, String datum, List<Reading> seaLevel,
                       List<Extreme> extremes, Quota quota,
                       Instant retrievedAt, boolean cached) {

    public TideData {
        seaLevel = List.copyOf(seaLevel);
        extremes = List.copyOf(extremes);
    }

    /** The same prediction, marked as having come from the cache at {@code when}. */
    public TideData fromCache(Instant when) {
        return new TideData(station, datum, seaLevel, extremes, quota, when, true);
    }

    /** The tide gauge the prediction actually came from. */
    public record Station(String name, String source, double distanceKm,
                          double lat, double lon) {

        /** What to print under the chart: where this is really from, and how far off. */
        public String describe() {
            final String where = (name == null || name.isBlank()) ? "an unnamed station" : name;
            return String.format(java.util.Locale.ROOT, "%s, %.0f km away", where, distanceKm);
        }
    }

    /** One point on the curve. */
    public record Reading(Instant time, double metres) { }

    /** A high or a low water. */
    public record Extreme(Instant time, double metres, Kind kind) {

        public enum Kind {
            HIGH("High"),
            LOW("Low");

            private final String displayName;

            Kind(String displayName) {
                this.displayName = displayName;
            }

            public String displayName() { return displayName; }
        }

        public boolean isHigh() { return kind == Kind.HIGH; }
    }

    /**
     * What the request cost and what is left today.
     *
     * <p>Carried into the window because the free tier is ten requests a day
     * and a tide chart costs two of them. A user who cannot see the count
     * discovers the limit by hitting it, which is the worst moment to learn
     * that panning to a sixth harbour was the expensive part.</p>
     */
    public record Quota(int requestCount, int dailyQuota) {

        public static final Quota UNKNOWN = new Quota(-1, -1);

        public boolean isKnown() { return dailyQuota > 0; }

        public String describe() {
            return isKnown() ? requestCount + " of " + dailyQuota + " requests used today" : "";
        }
    }

    /** The local dates this prediction has any curve for, in order. */
    public List<LocalDate> daysCovered(ZoneId zone) {
        final Set<LocalDate> days = new LinkedHashSet<>();
        for (Reading r : seaLevel) days.add(LocalDate.ofInstant(r.time(), zone));
        return List.copyOf(days);
    }

    /**
     * The curve for one local day, with the readings either side of it.
     *
     * <p>The neighbours matter: without them the line starts at the first
     * reading after midnight and stops at the last before the next, leaving a
     * gap at each end of the chart where the water is plainly still there.</p>
     */
    public List<Reading> curveFor(LocalDate day, ZoneId zone) {
        final Instant from = day.atStartOfDay(zone).toInstant();
        final Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();

        final List<Reading> out = new ArrayList<>();
        Reading before = null;
        for (Reading r : seaLevel) {
            if (r.time().isBefore(from)) {
                before = r;
                continue;
            }
            if (out.isEmpty() && before != null) out.add(before);
            out.add(r);
            if (!r.time().isBefore(to)) break;
        }
        return out;
    }

    /** The highs and lows falling on one local day, in time order. */
    public List<Extreme> extremesFor(LocalDate day, ZoneId zone) {
        final List<Extreme> out = new ArrayList<>();
        for (Extreme e : extremes) {
            if (LocalDate.ofInstant(e.time(), zone).equals(day)) out.add(e);
        }
        return out;
    }

    /** True when there is nothing to draw. */
    public boolean isEmpty() { return seaLevel.isEmpty() && extremes.isEmpty(); }
}
