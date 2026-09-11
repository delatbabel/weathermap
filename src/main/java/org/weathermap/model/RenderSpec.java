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

    /** The layers that can be switched on and off, drawn bottom to top in this order. */
    public enum LayerKind {
        /** Flat sea and land fill derived from the coastline. */
        LAND_SEA,
        /** Coastline strokes. */
        COASTLINE,
        /** National (and optionally regional) boundaries. */
        BOUNDARIES,
        /** The GRIB field itself. */
        GRIB,
        /** City, town and village labels with their dots. */
        PLACE_LABELS,
        /** Lat/lon graticule. */
        GRATICULE,
        /** Colour-ramp legend, model name and valid time. */
        ANNOTATION
    }

    private int maxWidth = 1600;
    private int maxHeight = 1200;
    private boolean mercator = false;
    private float gribOpacity = 0.65f;
    private boolean autoScaleRamp = true;
    private final Set<LayerKind> layers = EnumSet.of(
            LayerKind.LAND_SEA, LayerKind.COASTLINE, LayerKind.BOUNDARIES,
            LayerKind.GRIB, LayerKind.PLACE_LABELS, LayerKind.ANNOTATION);

    public int maxWidth() { return maxWidth; }

    public int maxHeight() { return maxHeight; }

    public void setMaxSize(int width, int height) {
        if (width < 64 || height < 64) throw new IllegalArgumentException("output is too small");
        this.maxWidth = width;
        this.maxHeight = height;
    }

    public boolean mercator() { return mercator; }

    public void setMercator(boolean mercator) { this.mercator = mercator; }

    /** 0 = base map only, 1 = the field hides what is under it. */
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
