package org.weathermap.model;

import java.util.List;
import java.util.Objects;

/**
 * A geographic rectangle in WGS84 degrees: the area the user selects, the area
 * OSM features are fetched for, and the {@code subregion} NOMADS is asked to cut
 * the GRIB down to.
 *
 * <p>Latitudes run south to north and are clamped to &plusmn;90. Longitudes run
 * west to east: {@code west} is in [-180, 180) and {@code east} in (-180, 180].</p>
 *
 * <h2>Crossing the antimeridian</h2>
 *
 * <p>A box <b>may</b> cross 180&deg;, and says so by having an east edge
 * numerically smaller than its west one: {@code west=170, east=-170} is the
 * twenty degrees of Pacific either side of the seam, not the three hundred and
 * forty degrees the other way round. There is no other way to write it down, so
 * the inverted pair is the notation rather than an error - which does mean a
 * transposed pair typed into the area fields now describes a very wide box
 * instead of being refused.</p>
 *
 * <p>Everything that measures longitude here goes through {@link #widthDegrees}
 * or {@link #eastwardFrom}, both of which understand the wrap, so a consumer
 * that uses them needs no special case. The two that cannot - Overpass's
 * {@code bbox} filter and NOMADS's {@code leftlon}/{@code rightlon}, which both
 * insist on west &lt; east - call {@link #halves} and issue one request per
 * side of the seam.</p>
 */
public final class BoundingBox {

    /** Smallest edge we will accept, in degrees - below this a render is a single pixel. */
    public static final double MIN_SPAN = 0.01;

    /** Slack for comparing spans that have been through a wrap. */
    private static final double EPSILON = 1e-9;

    private final double south;
    private final double west;
    private final double north;
    private final double east;

    private BoundingBox(double south, double west, double north, double east) {
        this.south = south;
        this.west = west;
        this.north = north;
        this.east = east;
    }

    /**
     * @throws IllegalArgumentException if the corners are out of range, inverted
     *                                  in latitude, or degenerate
     */
    public static BoundingBox of(double south, double west, double north, double east) {
        require(south >= -90 && south <= 90, "south out of range: " + south);
        require(north >= -90 && north <= 90, "north out of range: " + north);
        require(west >= -180 && west < 180, "west out of range: " + west);
        require(east > -180 && east <= 180, "east out of range: " + east);
        require(north > south, "north (" + north + ") must be above south (" + south + ")");
        require(east != west, "east and west are the same meridian (" + east
                + ") - a box of no width and a box of the whole world would be "
                + "written the same way; for the whole world use -180 and 180");
        require(north - south >= MIN_SPAN, "box is less than " + MIN_SPAN + " deg tall");
        require(span(west, east) >= MIN_SPAN, "box is less than " + MIN_SPAN + " deg wide");
        return new BoundingBox(south, west, north, east);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    /** The eastward distance from {@code west} to {@code east}, wrapping if it must. */
    private static double span(double west, double east) {
        final double d = east - west;
        return d < 0 ? d + 360 : d;
    }

    /** @return {@code lon} folded into [-180, 180), the range {@link #west} uses */
    public static double normaliseLon(double lon) {
        final double x = (lon + 180) % 360;
        return (x < 0 ? x + 360 : x) - 180;
    }

    /** @return {@code lon} folded into (-180, 180], the range {@link #east} uses */
    public static double normaliseEastLon(double lon) {
        final double x = normaliseLon(lon);
        return x == -180 ? 180 : x;
    }

    public double south() { return south; }
    public double west()  { return west; }
    public double north() { return north; }
    public double east()  { return east; }

    /** True when the box runs through 180&deg;, so that {@code east < west}. */
    public boolean crossesAntimeridian() { return east < west; }

    public double widthDegrees()  { return span(west, east); }
    public double heightDegrees() { return north - south; }

    public double centreLat() { return (south + north) / 2; }
    public double centreLon() { return normaliseLon(west + widthDegrees() / 2); }

    /**
     * How far east of the west edge a meridian lies, in degrees.
     *
     * <p>This is the one conversion a crossing box needs, and it is why the
     * projections have no antimeridian code of their own: {@code x} is
     * {@code eastwardFrom(lon) / widthDegrees()} of the way across the image
     * whether the box crosses the seam or not.</p>
     *
     * <p>The result is normally in [0, width], but a meridian just outside the
     * west edge comes back <em>negative</em> rather than as nearly a full turn,
     * so that a coastline entering the box from the west is drawn entering from
     * the left instead of leaping in from the right. The cut is made opposite
     * the box's own centre, which is as far from either edge as it can be
     * put.</p>
     */
    public double eastwardFrom(double lon) {
        final double width = widthDegrees();
        double d = lon - west;
        d -= 360 * Math.floor(d / 360);              // [0, 360)
        return d > (width + 360) / 2 ? d - 360 : d;
    }

    public boolean contains(double lat, double lon) {
        if (lat < south || lat > north) return false;
        final double d = eastwardFrom(lon);
        return d >= 0 && d <= widthDegrees();
    }

    /** True when every point of {@code other} lies within this box. */
    public boolean contains(BoundingBox other) {
        if (other.south < south || other.north > north) return false;
        if (widthDegrees() >= 360 - EPSILON) return true;
        final double start = eastwardFrom(other.west);
        return start >= -EPSILON
                && start + other.widthDegrees() <= widthDegrees() + EPSILON;
    }

    /**
     * Grows the box by {@code degrees} on every side.
     *
     * <p>Latitude is clamped at the poles; longitude wraps instead, because
     * there is no edge there to clamp against. A box grown past a full turn
     * becomes the whole world rather than overlapping itself.</p>
     */
    public BoundingBox expanded(double degrees) {
        final double s = Math.max(-90, south - degrees);
        final double n = Math.min(90, north + degrees);
        if (widthDegrees() + 2 * degrees >= 360 - EPSILON) return of(s, -180, n, 180);
        return of(s, normaliseLon(west - degrees), n, normaliseEastLon(east + degrees));
    }

    /**
     * The box as one or two pieces, none of which crosses the antimeridian.
     *
     * <p>For everything that asks a remote service for a rectangle. Overpass's
     * {@code bbox} and NOMADS's {@code leftlon}/{@code rightlon} both require
     * west &lt; east, so a crossing box is fetched as the strip up to 180&deg;
     * and the strip on from -180&deg;, in that order - west to east, so a
     * caller stitching the results back together can simply concatenate
     * them.</p>
     *
     * <p>A box that crosses by less than {@link #MIN_SPAN} gives one piece, not
     * a sliver: a hundredth of a degree is below the resolution of every source
     * here and under half a pixel on any chart, and a piece that thin is either
     * rejected outright or comes back as a single column of grid.</p>
     *
     * @return one box if this one does not cross, otherwise two, west first
     */
    public List<BoundingBox> halves() {
        if (!crossesAntimeridian()) return List.of(this);
        if (180 - west < MIN_SPAN)  return List.of(of(south, -180, north, east));
        if (east + 180 < MIN_SPAN)  return List.of(of(south, west, north, 180));
        return List.of(of(south, west, north, 180), of(south, -180, north, east));
    }

    /**
     * @return {@code south,west,north,east} - the order Overpass expects in its
     *         {@code bbox} filter.
     * @throws IllegalStateException if the box crosses the antimeridian, which
     *         Overpass cannot express; call {@link #halves} first
     */
    public String toOverpassBbox() {
        if (crossesAntimeridian()) {
            throw new IllegalStateException("a box crossing the antimeridian has no "
                    + "single Overpass bbox: " + this + " - query its halves()");
        }
        return fmt(south) + "," + fmt(west) + "," + fmt(north) + "," + fmt(east);
    }

    /** @return {@code west,south,east,north} - the order used for display and preferences. */
    @Override
    public String toString() {
        return fmt(west) + "," + fmt(south) + "," + fmt(east) + "," + fmt(north);
    }

    /** Parses the {@link #toString()} form. */
    public static BoundingBox parse(String s) {
        final String[] parts = s.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("expected west,south,east,north but got: " + s);
        }
        return of(Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[0].trim()),
                  Double.parseDouble(parts[3].trim()), Double.parseDouble(parts[2].trim()));
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.5f", d);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BoundingBox other)) return false;
        return Double.compare(south, other.south) == 0
                && Double.compare(west, other.west) == 0
                && Double.compare(north, other.north) == 0
                && Double.compare(east, other.east) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(south, west, north, east);
    }
}
