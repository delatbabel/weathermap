package org.weathermap.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * How the output image should be produced: size, projection, which layers are
 * on, and how hard the GRIB overlay is pushed.
 *
 * <p>Separate from {@link GribSelection} because it is a presentation choice,
 * not a data one: the same downloaded fields can be rendered at several sizes
 * without going back to the network.</p>
 */
public final class RenderSpec {

    /**
     * The layers that can be switched on and off.
     *
     * <p>Declaration order is not drawing order - {@link org.weathermap.render.Compositor}
     * decides that, and it puts the scalar fields at the bottom, the base map
     * over them and the wind barbs on top. The reasoning is in that class.</p>
     */
    public enum LayerKind {
        /** Flat sea and land fill derived from the coastline. */
        LAND_SEA,
        /** Scalar fields - temperature, precipitation, cloud - as colour washes. */
        GRIB,
        /** Lat/lon graticule. */
        GRATICULE,
        /** Coastline strokes. */
        COASTLINE,
        /** National (and optionally regional) boundaries. */
        BOUNDARIES,
        /** City, town and village labels with their dots. */
        PLACE_LABELS,
        /** Wind barbs. The top layer, and the point of the map. */
        WIND_BARBS,
        /** Colour-ramp legend, model name and valid time. */
        ANNOTATION
    }

    private int maxWidth = 1600;
    private int maxHeight = 1200;
    private boolean mercator = false;
    private float gribOpacity = 1.0f;
    private boolean autoScaleRamp = true;
    private final Set<LayerKind> layers = EnumSet.of(
            LayerKind.LAND_SEA, LayerKind.GRIB, LayerKind.COASTLINE, LayerKind.BOUNDARIES,
            LayerKind.PLACE_LABELS, LayerKind.WIND_BARBS, LayerKind.ANNOTATION);

    public int maxWidth() { return maxWidth; }

    public int maxHeight() { return maxHeight; }

    public void setMaxSize(int width, int height) {
        if (width < 64 || height < 64) throw new IllegalArgumentException("output is too small");
        this.maxWidth = width;
        this.maxHeight = height;
    }

    public boolean mercator() { return mercator; }

    public void setMercator(boolean mercator) { this.mercator = mercator; }

    /**
     * A master scale on every scalar field's opacity, not the opacity itself.
     *
     * <p>Each field carries its own weight - temperature is a pale wash,
     * precipitation a darker stain - because the right answer differs per field
     * and a single number cannot express both. This multiplies all of them, for
     * turning the whole wash up or down at once. 1 leaves each field as
     * designed; 0 removes them and leaves the base map and the barbs.</p>
     *
     * @see org.weathermap.render.FieldStyle
     */
    public float gribOpacity() { return gribOpacity; }

    public void setGribOpacity(float opacity) {
        this.gribOpacity = Math.max(0f, Math.min(1f, opacity));
    }

    /**
     * Whether a colour ramp is fitted to each field's own range.
     *
     * <p>On by default, because a fixed scale usually renders a single-region
     * field as one flat colour. Turn it off to compare maps against each other,
     * where the same colour must mean the same value every time.</p>
     */
    public boolean autoScaleRamp() { return autoScaleRamp; }

    public void setAutoScaleRamp(boolean on) { this.autoScaleRamp = on; }

    public boolean isEnabled(LayerKind kind) { return layers.contains(kind); }

    public void setEnabled(LayerKind kind, boolean on) {
        if (on) layers.add(kind); else layers.remove(kind);
    }

    public Set<LayerKind> layers() { return EnumSet.copyOf(layers); }

    /** Builds the projection this spec asks for, sized to the box's aspect ratio. */
    public MapProjection projectionFor(BoundingBox bbox) {
        final int[] size = MapProjection.fitSize(bbox, maxWidth, maxHeight, mercator);
        return mercator
                ? new MercatorProjection(bbox, size[0], size[1])
                : new EquirectangularProjection(bbox, size[0], size[1]);
    }

    public RenderSpec copy() {
        final RenderSpec c = new RenderSpec();
        c.maxWidth = maxWidth;
        c.maxHeight = maxHeight;
        c.mercator = mercator;
        c.gribOpacity = gribOpacity;
        c.autoScaleRamp = autoScaleRamp;
        c.layers.clear();
        c.layers.addAll(layers);
        return c;
    }
}
