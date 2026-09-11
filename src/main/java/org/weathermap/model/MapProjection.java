package org.weathermap.model;

import java.awt.geom.Point2D;

/**
 * Maps WGS84 degrees onto the pixels of the output image, and back.
 *
 * <p>Every layer draws through the same projection instance, which is what keeps
 * the coastline, the boundaries, the place labels and the GRIB field in
 * register. A projection is created once per render from the
 * {@link BoundingBox} and the output size, and is immutable thereafter.</p>
 *
 * <p>Pixel space has its origin at the top-left of the image, x increasing east
 * and y increasing <em>south</em> - the Java2D convention, not the geographic
 * one.</p>
 */
public interface MapProjection {

    /** @return pixel coordinates for a geographic position; may fall outside the image. */
    Point2D.Double toPixel(double lat, double lon);

    /** @return the geographic position of a pixel, as {@code {lat, lon}}. */
    double[] toLatLon(double x, double y);

    /** The area this projection covers. */
    BoundingBox bounds();

    int imageWidth();

    int imageHeight();

    /**
     * The output size that gives {@code bbox} its true aspect ratio at this
     * projection, fitted inside {@code maxWidth} x {@code maxHeight}.
     *
     * <p>Called before the projection exists, so it is static: the UI needs the
     * size to lay out the preview, and the CLI needs it to size the PNG.</p>
     */
    static int[] fitSize(BoundingBox bbox, int maxWidth, int maxHeight, boolean mercator) {
        final double w = bbox.widthDegrees();
        final double h = mercator
                ? MercatorProjection.mercatorY(bbox.north()) - MercatorProjection.mercatorY(bbox.south())
                : bbox.heightDegrees();
        final double aspect = w / h;
        int width = maxWidth;
        int height = (int) Math.round(width / aspect);
        if (height > maxHeight) {
            height = maxHeight;
            width = (int) Math.round(height * aspect);
        }
        return new int[]{Math.max(1, width), Math.max(1, height)};
    }
}
