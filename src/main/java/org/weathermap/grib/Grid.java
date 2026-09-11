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
        final double fx = (lon - bounds.west()) / bounds.widthDegrees() * (width - 1);
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
