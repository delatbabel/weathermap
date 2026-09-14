package org.weathermap.model;

import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
        /** Isobars and other isolines, drawn over the base map. */
        ISOBARS,
        /** Wind barbs. The top layer, and the point of the map. */
        WIND_BARBS,
        /** Colour-ramp legend, model name and valid time. */
        ANNOTATION
    }

    /**
     * The zone the chart's times are written in.
     *
     * <p>Defaults to the machine's own, because the times on a chart are read by
     * whoever is standing in front of it, and "valid 06:00 UTC" makes someone in
     * Bangkok do arithmetic before they can tell whether it is this afternoon.
     * The forecast itself is unaffected: this changes how an instant is written
     * down, never which instant it is.</p>
     *
     * <p>Deliberately not applied to file names, which stay UTC so that a
     * directory of charts sorts chronologically no matter who generated it or
     * where they were.</p>
     */
    private ZoneId zone = ZoneId.systemDefault();

    /**
     * Names to print instead of the ones the map data carries.
     *
     * <h2>Why this is a setting and not a correction</h2>
     *
     * <p>Some water has more than one name, and which one is right depends on
     * who is reading. The bundled gazetteer calls one body of water the South
     * China Sea; in Vietnam it is the East Sea, and a chart published there
     * carrying the other name is not a chart anyone will publish. The same is
     * true of several others, in both directions.</p>
     *
     * <p>So the application does not choose. It ships with no renames at all
     * and takes whatever the publisher sets, because the publisher is the one
     * who knows their audience and is answerable for the caption. Nothing here
     * changes the data, only what is drawn over it.</p>
     */
    private final Map<String, String> labelNames = new LinkedHashMap<>();

    private int maxWidth = 1600;
    private int maxHeight = 1200;
    private boolean mercator = false;
    private float gribOpacity = 1.0f;
    private boolean autoScaleRamp = true;
    private final Set<LayerKind> layers = EnumSet.of(
            LayerKind.LAND_SEA, LayerKind.GRIB, LayerKind.COASTLINE, LayerKind.BOUNDARIES,
            LayerKind.PLACE_LABELS, LayerKind.ISOBARS, LayerKind.WIND_BARBS,
            LayerKind.ANNOTATION);

    /** The name to draw for a feature, which is usually its own. */
    public String nameFor(String name) {
        if (name == null || labelNames.isEmpty()) return name;
        return labelNames.getOrDefault(name, name);
    }

    /** Every rename in force, in the order they were given. */
    public Map<String, String> labelNames() { return Map.copyOf(labelNames); }

    /**
     * Prints {@code to} wherever the map data says {@code from}.
     *
     * @param to blank or equal to {@code from} removes the rename
     */
    public void renameLabel(String from, String to) {
        if (from == null || from.isBlank()) return;
        final String was = from.trim();
        if (to == null || to.isBlank() || to.trim().equals(was)) {
            labelNames.remove(was);
            return;
        }
        labelNames.put(was, to.trim());
    }

    public void clearLabelNames() { labelNames.clear(); }

    public ZoneId zone() { return zone; }

    public void setZone(ZoneId zone) {
        this.zone = (zone == null) ? ZoneId.systemDefault() : zone;
    }

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

    /**
     * A copy that the renderer can hold while the window keeps being edited.
     *
     * <p>Field by field, which is a liability: anything added to this class and
     * not added here is a setting that works from the command line - which does
     * not copy - and silently does nothing in the window. That has happened
     * twice, most recently to the chart time zone, which meant every chart from
     * the window was stamped in the machine's zone whatever the menu said.
     * {@code CopyCompletenessTest} now compares every declared field after a
     * copy, so the next omission fails a test rather than shipping.</p>
     */
    public RenderSpec copy() {
        final RenderSpec c = new RenderSpec();
        c.maxWidth = maxWidth;
        c.maxHeight = maxHeight;
        c.mercator = mercator;
        c.gribOpacity = gribOpacity;
        c.autoScaleRamp = autoScaleRamp;
        c.zone = zone;
        c.layers.clear();
        c.layers.addAll(layers);
        c.labelNames.putAll(labelNames);
        return c;
    }
}
