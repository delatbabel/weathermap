package org.weathermap.model;

import java.awt.geom.Point2D;

/**
 * Web Mercator (spherical), for when the output has to line up with slippy-map
 * tiles or with what people expect a weather map to look like.
 *
 * <p>Conformal, so weather systems keep their shape - but a GRIB field drawn
 * through it must be resampled row by row, because equal steps in latitude are
 * not equal steps in y. {@link org.weathermap.render.GribLayer} does that by
 * sampling per output pixel rather than per grid cell.</p>
 *
 * <p>Undefined at the poles; latitudes are clamped to the usual
 * &plusmn;85.05113 so the transform stays finite.</p>
 */
public final class MercatorProjection implements MapProjection {

    /** The latitude at which Web Mercator is conventionally truncated. */
    public static final double MAX_LAT = 85.05112877980659;

    private final BoundingBox bounds;
    private final int width;
    private final int height;
    private final double yNorth;
    private final double ySpan;

    public MercatorProjection(BoundingBox bounds, int width, int height) {
        this.bounds = bounds;
        this.width = width;
        this.height = height;
        this.yNorth = mercatorY(bounds.north());
        this.ySpan = yNorth - mercatorY(bounds.south());
    }

    /** The Mercator y of a latitude, in the same units as longitude degrees. */
    public static double mercatorY(double lat) {
        final double clamped = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
        final double rad = Math.toRadians(clamped);
        return Math.toDegrees(Math.log(Math.tan(Math.PI / 4 + rad / 2)));
    }

    /** The inverse of {@link #mercatorY}. */
    public static double latFromMercatorY(double y) {
        return Math.toDegrees(2 * Math.atan(Math.exp(Math.toRadians(y))) - Math.PI / 2);
    }

    @Override
    public Point2D.Double toPixel(double lat, double lon) {
        final double x = (lon - bounds.west()) / bounds.widthDegrees() * width;
        final double y = (yNorth - mercatorY(lat)) / ySpan * height;
        return new Point2D.Double(x, y);
    }

    @Override
    public double[] toLatLon(double x, double y) {
        final double lon = bounds.west() + x / width * bounds.widthDegrees();
        final double lat = latFromMercatorY(yNorth - y / height * ySpan);
        return new double[]{lat, lon};
    }

    @Override
    public BoundingBox bounds() { return bounds; }

    @Override
    public int imageWidth() { return width; }

    @Override
    public int imageHeight() { return height; }
}
