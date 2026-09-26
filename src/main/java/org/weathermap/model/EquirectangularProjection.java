package org.weathermap.model;

import java.awt.geom.Point2D;

/**
 * Plate carree: longitude and latitude scale linearly onto x and y.
 *
 * <p>The default, because it is what GRIB grids are already in. A GFS 0.25-degree
 * field is a regular lat/lon array, so compositing it needs no resampling beyond
 * the scale factor - every other projection means interpolating the field.</p>
 *
 * <p>The cost is the usual one: shapes stretch east-west towards the poles. For
 * the mid-latitude regions this application is aimed at that is acceptable; use
 * {@link MercatorProjection} when it is not.</p>
 *
 * <p>Longitude goes through {@link BoundingBox#eastwardFrom}, so a box that
 * crosses the antimeridian projects like any other: the seam falls somewhere in
 * the middle of the image rather than at an edge, and nothing here has to know
 * it is there.</p>
 */
public final class EquirectangularProjection implements MapProjection {

    private final BoundingBox bounds;
    private final int width;
    private final int height;

    public EquirectangularProjection(BoundingBox bounds, int width, int height) {
        this.bounds = bounds;
        this.width = width;
        this.height = height;
    }

    @Override
    public Point2D.Double toPixel(double lat, double lon) {
        final double x = bounds.eastwardFrom(lon) / bounds.widthDegrees() * width;
        final double y = (bounds.north() - lat) / bounds.heightDegrees() * height;
        return new Point2D.Double(x, y);
    }

    @Override
    public double[] toLatLon(double x, double y) {
        final double lon = BoundingBox.normaliseLon(
                bounds.west() + x / width * bounds.widthDegrees());
        final double lat = bounds.north() - y / height * bounds.heightDegrees();
        return new double[]{lat, lon};
    }

    @Override
    public BoundingBox bounds() { return bounds; }

    @Override
    public int imageWidth() { return width; }

    @Override
    public int imageHeight() { return height; }
}
