package org.weathermap.model;

import java.util.Objects;

/**
 * A geographic rectangle in WGS84 degrees: the area the user selects, the area
 * OSM features are fetched for, and the {@code subregion} NOMADS is asked to cut
 * the GRIB down to.
 *
 * <p>Latitudes run south to north and are clamped to &plusmn;90. Longitudes run
 * west to east in [-180, 180).</p>
 *
 * <p><b>The antimeridian is not handled.</b> A box whose west edge is greater
 * than its east edge (one spanning 180&deg;) is rejected rather than silently
 * split, because every consumer downstream - the Overpass query, the NOMADS
 * {@code leftlon}/{@code rightlon} parameters and the projection - would need to
 * understand the split for the result to be correct. Supporting it means
 * splitting into two boxes at the seam and compositing the halves; see
 * README.md.</p>
 */
public final class BoundingBox {

    /** Smallest edge we will accept, in degrees - below this a render is a single pixel. */
    public static final double MIN_SPAN = 0.01;

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
     * @throws IllegalArgumentException if the corners are out of range, inverted,
     *                                  degenerate, or cross the antimeridian
     */
    public static BoundingBox of(double south, double west, double north, double east) {
        require(south >= -90 && south <= 90, "south out of range: " + south);
        require(north >= -90 && north <= 90, "north out of range: " + north);
        require(west >= -180 && west < 180, "west out of range: " + west);
        require(east > -180 && east <= 180, "east out of range: " + east);
        require(north > south, "north (" + north + ") must be above south (" + south + ")");
        require(east > west, "east (" + east + ") must be right of west (" + west
                + ") - boxes crossing the antimeridian are not supported");
        require(north - south >= MIN_SPAN, "box is less than " + MIN_SPAN + " deg tall");
        require(east - west >= MIN_SPAN, "box is less than " + MIN_SPAN + " deg wide");
        return new BoundingBox(south, west, north, east);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    public double south() { return south; }
    public double west()  { return west; }
    public double north() { return north; }
    public double east()  { return east; }

    public double widthDegrees()  { return east - west; }
    public double heightDegrees() { return north - south; }

    public double centreLat() { return (south + north) / 2; }
    public double centreLon() { return (west + east) / 2; }

    public boolean contains(double lat, double lon) {
        return lat >= south && lat <= north && lon >= west && lon <= east;
    }

    /** Grows the box by {@code degrees} on every side, clamped to valid ranges. */
    public BoundingBox expanded(double degrees) {
        return of(Math.max(-90, south - degrees),
                  Math.max(-180, west - degrees),
                  Math.min(90, north + degrees),
                  Math.min(180, east + degrees));
    }

    /**
     * @return {@code south,west,north,east} - the order Overpass expects in its
     *         {@code bbox} filter.
     */
    public String toOverpassBbox() {
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
