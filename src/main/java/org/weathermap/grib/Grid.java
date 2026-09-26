package org.weathermap.grib;

import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribLevel;
import org.weathermap.model.GribVariable;

/**
 * One decoded GRIB field: a regular lat/lon array of values with the metadata
 * needed to draw and label it.
 *
 * <p>This is the boundary between "GRIB" and "picture". Everything upstream
 * deals in messages, templates and packing; everything downstream deals in this.
 * A second {@link GribReader} implementation therefore changes nothing in the
 * renderer.</p>
 *
 * <p>Values are stored row-major from the <b>north-west</b> corner, x increasing
 * east and y increasing south - matching both the Java2D convention and the
 * scanning mode GFS actually uses, so no flip is needed on the common path. A
 * reader whose source scans differently must normalise before constructing.</p>
 */
public final class Grid {

    /** The value used for a point the GRIB bitmap marks as missing. */
    public static final float MISSING = Float.NaN;

    private final GribVariable variable;
    private final GribLevel level;
    private final java.time.Instant validTime;
    private final BoundingBox bounds;
    private final int width;
    private final int height;
    private final float[] values;

    public Grid(GribVariable variable, GribLevel level, java.time.Instant validTime,
                BoundingBox bounds, int width, int height, float[] values) {
        if (values.length != width * height) {
            throw new IllegalArgumentException(
                    "expected " + width * height + " values but got " + values.length);
        }
        this.variable = variable;
        this.level = level;
        this.validTime = validTime;
        this.bounds = bounds;
        this.width = width;
        this.height = height;
        this.values = values;
    }

    public GribVariable variable() { return variable; }

    public GribLevel level() { return level; }

    /** When this forecast is for, not when it was produced. */
    public java.time.Instant validTime() { return validTime; }

    /** The area the grid covers - the cell centres of the corners. */
    public BoundingBox bounds() { return bounds; }

    public int width() { return width; }

    public int height() { return height; }

    /** @return the value at a grid index, or {@link #MISSING} */
    public float valueAt(int x, int y) {
        return values[y * width + x];
    }

    /**
     * Bilinear sample at a geographic position, which is what the renderer needs:
     * output pixels do not line up with grid cells once the box has been fitted
     * to an image size, and never do under Mercator.
     *
     * @return the interpolated value, or {@link #MISSING} outside the grid or
     *         where any contributing cell is missing
     */
    public float sample(double lat, double lon) {
        final double fx = bounds.eastwardFrom(lon) / bounds.widthDegrees() * (width - 1);
        final double fy = (bounds.north() - lat) / bounds.heightDegrees() * (height - 1);
        if (fx < 0 || fy < 0 || fx > width - 1 || fy > height - 1) return MISSING;

        final int x0 = (int) Math.floor(fx);
        final int y0 = (int) Math.floor(fy);
        final int x1 = Math.min(x0 + 1, width - 1);
        final int y1 = Math.min(y0 + 1, height - 1);
        final double tx = fx - x0;
        final double ty = fy - y0;

        final float v00 = valueAt(x0, y0);
        final float v10 = valueAt(x1, y0);
        final float v01 = valueAt(x0, y1);
        final float v11 = valueAt(x1, y1);
        if (Float.isNaN(v00) || Float.isNaN(v10) || Float.isNaN(v01) || Float.isNaN(v11)) {
            return MISSING;
        }
        final double top = v00 + (v10 - v00) * tx;
        final double bottom = v01 + (v11 - v01) * tx;
        return (float) (top + (bottom - top) * ty);
    }

    /**
     * Two halves of a field either side of the antimeridian, joined into one.
     *
     * <p>NOMADS cannot cut a subregion that crosses 180&deg; - {@code leftlon}
     * must be west of {@code rightlon} - so a crossing chart is fetched as two
     * subregions and stitched back together here, before anything downstream
     * sees it. That matters more than it sounds: the alternative, handing the
     * renderer two grids of the same variable, would give the legend two ranges
     * to scale from and would break every isobar at the seam.</p>
     *
     * <p>The two requests abut at 180&deg; and both include that meridian, so
     * the eastern half's leading column repeats the western half's last one; it
     * is dropped rather than drawn twice. The result is a regular grid again,
     * whose bounds cross the antimeridian in the same way {@link BoundingBox}
     * does.</p>
     *
     * @param west the half up to 180&deg;, and {@code east} the half on from -180&deg;
     * @throws IllegalArgumentException if the halves do not share their rows,
     *                                  which means they are not halves of one field
     */
    public static Grid join(Grid west, Grid east) {
        if (west.height != east.height
                || Double.compare(west.bounds.north(), east.bounds.north()) != 0
                || Double.compare(west.bounds.south(), east.bounds.south()) != 0) {
            throw new IllegalArgumentException("cannot join " + west + " to " + east
                    + ": they do not cover the same rows");
        }

        // The eastern half repeats whatever columns the western one already has.
        // Which, and how many, is read off the geometry rather than assumed, so
        // an over-fetched half joins as cleanly as an exact one.
        final double step = east.bounds.widthDegrees() / Math.max(1, east.width - 1);
        final double gap = BoundingBox.normaliseLon(east.bounds.west() - west.bounds.east());
        int drop = 0;
        while (drop < east.width - 1 && gap + drop * step <= step / 2) drop++;

        final int kept = east.width - drop;
        final int w = west.width + kept;
        final float[] values = new float[w * west.height];
        for (int y = 0; y < west.height; y++) {
            System.arraycopy(west.values, y * west.width, values, y * w, west.width);
            for (int x = 0; x < kept; x++) {
                values[y * w + west.width + x] = east.valueAt(drop + x, y);
            }
        }

        final BoundingBox bounds = BoundingBox.of(
                west.bounds.south(), west.bounds.west(),
                west.bounds.north(), east.bounds.east());
        return new Grid(west.variable, west.level, west.validTime,
                        bounds, w, west.height, values);
    }

    /** @return {@code {min, max}} over the non-missing values, for scaling the ramp. */
    public float[] range() {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (float v : values) {
            if (Float.isNaN(v)) continue;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        return (min > max) ? new float[]{0, 0} : new float[]{min, max};
    }

    @Override
    public String toString() {
        return variable.displayName() + " @ " + level.displayName()
                + " " + width + "x" + height + " valid " + validTime;
    }
}
