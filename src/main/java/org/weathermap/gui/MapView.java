package org.weathermap.gui;

import org.weathermap.model.BoundingBox;

/**
 * What the selection map is currently looking at, and the zoom and pan
 * operations on it.
 *
 * <p>Kept apart from {@link org.weathermap.model.BoundingBox} because the two
 * mean different things and the distinction matters: the view is where the user
 * is looking, the {@link MapPanel}'s selection is what they have chosen to
 * download. Panning does not change what will be fetched.</p>
 *
 * <p>Deliberately mutable and single-threaded - it is only ever touched on the
 * EDT, in response to mouse input.</p>
 */
public final class MapView {

    /** Zoomed in past this and the map is finer than any data source here. */
    public static final double MIN_SPAN = 0.05;

    /** The whole world, in degrees of longitude. */
    public static final double MAX_SPAN = 360.0;

    private double centreLat;
    private double centreLon;

    /** Longitude span of the view; latitude span follows from the aspect ratio. */
    private double spanLon;

    public MapView(BoundingBox initial) {
        setTo(initial);
    }

    /** Frames {@code bbox} with a little margin, so its edges are not flush. */
    public void setTo(BoundingBox bbox) {
        this.centreLat = bbox.centreLat();
        this.centreLon = bbox.centreLon();
        this.spanLon = clampSpan(bbox.widthDegrees() * 1.25);
    }

    public double centreLat() { return centreLat; }

    public double centreLon() { return centreLon; }

    public double spanLon() { return spanLon; }

    /**
     * The view as a bounding box for an image of this size.
     *
     * <p>The latitude span is derived from the aspect ratio rather than stored,
     * so a resized window shows more or less map instead of stretching what it
     * has.</p>
     */
    public BoundingBox bounds(int imageWidth, int imageHeight) {
        final double halfLon = spanLon / 2;
        final double halfLat = halfLon * imageHeight / Math.max(1, imageWidth);

        // Clamping latitude first, then re-deriving, keeps the centre honest
        // near the poles instead of silently sliding the view sideways.
        double north = centreLat + halfLat;
        double south = centreLat - halfLat;
        if (north > 90) {
            south -= north - 90;
            north = 90;
        }
        if (south < -90) {
            north += -90 - south;
            south = -90;
        }
        north = Math.min(90, north);
        south = Math.max(-90, south);

        // Longitude wraps instead of clamping. There is no edge at 180 to stop
        // at - the map continues - and stopping there is what used to make a
        // Pacific area impossible to choose in one go: the view could be walked
        // up to the antimeridian and no further, so an area spanning it had to
        // be taken in two halves.
        if (spanLon >= MAX_SPAN) {
            // A whole turn written as west == east would be indistinguishable
            // from no width at all, so the world is spelled out.
            return boundsOf(south, -180, north, 180);
        }
        final double west = BoundingBox.normaliseLon(centreLon - halfLon);
        final double east = BoundingBox.normaliseEastLon(centreLon + halfLon);

        return boundsOf(south, west, north, east);
    }

    /**
     * A view can legitimately be narrower than a selectable box, so this falls
     * back rather than throws - {@code MIN_SPAN} is a selection rule, not a
     * view one.
     */
    private BoundingBox boundsOf(double south, double west, double north, double east) {
        try {
            return BoundingBox.of(south, west, north, east);
        }
        catch (IllegalArgumentException e) {
            return BoundingBox.of(Math.max(-90, centreLat - 0.05),
                                  BoundingBox.normaliseLon(centreLon - 0.05),
                                  Math.min(90, centreLat + 0.05),
                                  BoundingBox.normaliseEastLon(centreLon + 0.05));
        }
    }

    /**
     * Zooms about a fixed geographic point - the one under the cursor - so the
     * map appears to scale around the pointer rather than the centre. Anything
     * else feels wrong to anyone who has used a map before.
     *
     * @param factor  below 1 zooms in
     * @param atLat   the point to hold still
     * @param atLon   the point to hold still
     */
    public void zoomAbout(double factor, double atLat, double atLon) {
        final double newSpan = clampSpan(spanLon * factor);
        final double actual = newSpan / spanLon;         // what the clamp allowed

        // The centre is taken in whichever turn of longitude sits nearest the
        // fixed point, or zooming just west of the antimeridian about a point
        // just east of it would fling the map most of the way round the world.
        final double centreNearby = atLon + BoundingBox.normaliseLon(centreLon - atLon);
        centreLon = atLon + (centreNearby - atLon) * actual;
        centreLat = atLat + (centreLat - atLat) * actual;
        spanLon = newSpan;
        clampCentre();
    }

    /** Moves the view by a geographic offset. */
    public void panBy(double deltaLat, double deltaLon) {
        centreLat += deltaLat;
        centreLon += deltaLon;
        clampCentre();
    }

    /** Latitude stops at the poles; longitude goes round. */
    private void clampCentre() {
        centreLat = Math.max(-90, Math.min(90, centreLat));
        centreLon = BoundingBox.normaliseLon(centreLon);
    }

    private static double clampSpan(double span) {
        return Math.max(MIN_SPAN, Math.min(MAX_SPAN, span));
    }
}
