package org.weathermap.render;

import org.weathermap.grib.Grid;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribCatalog;
import org.weathermap.model.MapProjection;
import org.weathermap.model.RenderSpec;
import org.weathermap.osm.Feature;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Stacks the layers into one image.
 *
 * <p>The entire drawing path, shared unchanged between the desktop application
 * and the command-line tool. The UI shows what this returns; the CLI writes it
 * to a PNG. There is no second renderer and no "preview quality" - a difference
 * between what is seen and what is saved is a class of bug this avoids by
 * construction.</p>
 *
 * <p>Layers are built here rather than passed in because the order is a property
 * of the map, not of the caller, and because pairing decisions - wind u with
 * wind v, a field with its ramp - belong somewhere that can see the whole set.</p>
 */
public final class Compositor {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(Compositor.class.getName());

    private final RenderSpec spec;

    public Compositor(RenderSpec spec) {
        this.spec = spec;
    }

    /**
     * Renders one map.
     *
     * @param bbox      the area
     * @param features  OSM features covering it; may be empty, in which case only
     *                  the fill and the GRIB field are drawn
     * @param grids     decoded fields for one forecast hour; {@link #primaryOf}
     *                  picks the one that drives the legend and the title
     * @param modelName for the annotation line
     */
    public BufferedImage render(BoundingBox bbox, List<Feature> features,
                                List<Grid> grids, String modelName) {
        final MapProjection projection = spec.projectionFor(bbox);
        final BufferedImage image = new BufferedImage(
                projection.imageWidth(), projection.imageHeight(), BufferedImage.TYPE_INT_RGB);

        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                               RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                               RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                               RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                               RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // TYPE_INT_RGB has no alpha, so an explicit ground is needed before
            // anything translucent is drawn over it.
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, projection.imageWidth(), projection.imageHeight());

            for (Layer layer : buildLayers(features, grids, modelName)) {
                if (!spec.isEnabled(layer.kind())) continue;
                final Graphics2D scratch = (Graphics2D) g.create();
                try {
                    layer.draw(scratch, projection);
                }
                finally {
                    scratch.dispose();
                }
            }
        }
        finally {
            g.dispose();
        }
        return image;
    }

    /**
     * Builds the stack, bottom first.
     *
     * <p>Place labels sit <em>above</em> the GRIB field deliberately: a
     * translucent temperature ramp over a city name makes it unreadable, and the
     * name is what tells the reader where they are looking.</p>
     */
    private List<Layer> buildLayers(List<Feature> features, List<Grid> grids, String modelName) {
        final List<Layer> layers = new ArrayList<>();

        // --- bottom: the fields, as colour under everything else -----------
        //
        // The order below is the whole argument of this class, so it is worth
        // stating. A marine chart is read for the wind; the coastline is what
        // tells you where the wind is. Both have to survive whatever else is
        // drawn, so the scalar fields go underneath rather than over the top,
        // which is the opposite of what a "weather overlay" usually means. A
        // temperature wash painted over a coastline does not make the
        // temperature clearer - it makes the coastline worse.

        // TODO: decide land-versus-sea default from whether the box contains any
        // coastline rather than assuming land. See VectorLayers.LandSeaLayer.
        layers.add(new VectorLayers.LandSeaLayer(true));

        final List<Grid> fields = distinct(grids);
        final Grid primaryGrid = primaryOf(fields);
        final List<IsolineLayer> isolines = new ArrayList<>();
        ColourRamp primaryRamp = null;
        Grid uGrid = null;
        Grid vGrid = null;

        for (Grid grid : fields) {
            final String code = grid.variable().code();
            // Wind is held back: its two components are not fields to be washed
            // over the map but one vector to be drawn on top of it.
            if (GribCatalog.WIND_U.code().equals(code) && uGrid == null) {
                uGrid = grid;
                continue;
            }
            if (GribCatalog.WIND_V.code().equals(code) && vGrid == null) {
                vGrid = grid;
                continue;
            }

            // A field is a wash, a set of lines, or both - the variable says
            // which. Pressure is the reason: painted as a wash it is a
            // meaningless pastel gradient, and drawn as isobars it is the thing
            // the chart is read by.
            final org.weathermap.model.GribVariable.RenderStyle drawn = grid.variable().style();
            if (isFilled(drawn)) {
                final FieldStyle style = FieldStyle.forGrid(grid, spec.autoScaleRamp());
                layers.add(new GribLayer(grid, style.ramp(),
                                         style.opacity() * spec.gribOpacity()));
                if (grid == primaryGrid) primaryRamp = style.ramp();
            }
            if (isContoured(drawn)) isolines.add(new IsolineLayer(grid));
        }

        // --- middle: the base map, over the fields --------------------------
        layers.add(new GraticuleLayer());
        layers.add(new VectorLayers.CoastlineLayer(features, 1.4f));
        layers.add(new VectorLayers.BoundaryLayer(features, 2));
        layers.add(new VectorLayers.PlaceLabelLayer(features, spec::nameFor));

        // --- isolines, over the base map -------------------------------------
        //
        // Above the coastline because that is where a chart puts them: an
        // isobar crossing a coast is normal and reads correctly, while a
        // coastline drawn over the pressure field breaks the one line whose
        // continuity carries the meaning. Still below the barbs, which stay the
        // top layer.
        layers.addAll(isolines);

        // --- top: the wind ---------------------------------------------------
        if (uGrid != null && vGrid != null) {
            layers.add(new WindBarbLayer(uGrid, vGrid));
        }
        else if (uGrid != null || vGrid != null) {
            // One component without the other cannot be drawn. Saying so beats
            // a map that silently has no wind on it.
            LOG.warning("Wind needs both UGRD and VGRD; only "
                    + (uGrid != null ? "UGRD" : "VGRD") + " was decoded, so no barbs "
                    + "were drawn. Select \"Wind\" rather than a single component.");
        }

        // Chrome last, so the legend stays readable. It occupies two corners
        // with an opaque backing and does not compete with the barbs.
        final boolean hasWind = uGrid != null && vGrid != null;
        layers.add(new AnnotationLayer(primaryGrid, primaryRamp, modelName, hasWind,
                                       validTimeOf(fields), attributionsFor(features),
                                       isolines.isEmpty() ? null : isolines.get(0),
                                       java.time.Instant.now(), spec.zone()));
        return layers;
    }

    /**
     * The map-data credits this chart has actually earned.
     *
     * <p>Derived from the features rather than fixed, because which sources were
     * used is decided at fetch time and can change between one chart and the
     * next: a wide area is drawn entirely from the bundle, a narrow one mostly
     * from OSM, and one where Overpass timed out from the bundle after asking.
     * OpenStreetMap's licence requires the credit when its data is present -
     * and printing it when the data is absent is a small untruth on every
     * copy.</p>
     */
    static List<String> attributionsFor(List<Feature> features) {
        boolean osm = false;
        boolean bundled = false;
        for (Feature f : features) {
            if (org.weathermap.osm.WorldGazetteer.isBundled(f)) bundled = true;
            else osm = true;
            if (osm && bundled) break;
        }

        final List<String> out = new ArrayList<>();
        if (osm) out.add(AnnotationLayer.OSM_ATTRIBUTION);
        if (bundled) out.add(org.weathermap.osm.WorldGazetteer.ATTRIBUTION);
        // A chart with no base map at all still says where its numbers came
        // from, which the caller adds after these.
        return out;
    }

    /**
     * The field the chart is named and captioned for.
     *
     * <p>Public and static because {@link org.weathermap.MapService} names the
     * PNG file from it, and a file called {@code UGRD} whose title reads
     * "Precipitation" is a small lie that takes a while to notice. Before this
     * existed they picked independently - the file took the first decoded grid,
     * which the wind hold-back made a component that is never the subject.</p>
     *
     * <p>Wind is the subject of these charts but it is drawn, not shaded, so it
     * only becomes the primary when nothing else was decoded.</p>
     */
    public static Grid primaryOf(List<Grid> grids) {
        // A washed field first, because the legend describes a colour ramp and
        // there is no ramp to describe for one drawn as lines. Pressure named
        // the chart and suppressed the precipitation legend at the same time.
        for (Grid grid : grids) {
            if (isFilled(grid.variable().style())) return grid;
        }
        for (Grid grid : grids) {
            if (!FieldStyle.isVectorComponent(grid.variable())) return grid;
        }
        return grids.isEmpty() ? null : grids.get(0);
    }

    static boolean isFilled(org.weathermap.model.GribVariable.RenderStyle style) {
        return style == org.weathermap.model.GribVariable.RenderStyle.FILLED_CONTOUR
                || style == org.weathermap.model.GribVariable.RenderStyle.FILLED_AND_LINES;
    }

    static boolean isContoured(org.weathermap.model.GribVariable.RenderStyle style) {
        return style == org.weathermap.model.GribVariable.RenderStyle.CONTOUR_LINES
                || style == org.weathermap.model.GribVariable.RenderStyle.FILLED_AND_LINES;
    }

    /**
     * Drops a field that has already been seen.
     *
     * <p>NOMADS returns accumulated precipitation for one forecast hour more
     * than once - a request for {@code APCP} at f006 comes back with two
     * records carrying identical values - and drawing a translucent wash twice
     * composites it twice, so the second copy silently darkens the first. The
     * duplicate is worth dropping rather than tolerating, because nothing
     * downstream can tell it apart from a field that is genuinely that
     * strong.</p>
     */
    static List<Grid> distinct(List<Grid> grids) {
        final List<Grid> out = new ArrayList<>(grids.size());
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (Grid grid : grids) {
            final String key = grid.variable().code() + "/" + grid.level().code()
                    + "/" + grid.validTime() + "/" + grid.width() + "x" + grid.height();
            if (seen.add(key)) out.add(grid);
            else LOG.fine(() -> "dropping a duplicate " + key);
        }
        return out;
    }

    /**
     * The instant the chart depicts.
     *
     * <p>The latest valid time among the fields, not the primary's own, because
     * an accumulation reports the <em>start</em> of its window: precipitation
     * for forecast hour 6 carries 00:00, and a chart of 06:00 winds captioned
     * "valid 00:00" is wrong in the one way a forecast must never be.</p>
     */
    public static java.time.Instant validTimeOf(List<Grid> grids) {
        java.time.Instant latest = null;
        for (Grid grid : grids) {
            final java.time.Instant t = grid.validTime();
            if (t != null && (latest == null || t.isAfter(latest))) latest = t;
        }
        return latest;
    }

    public RenderSpec spec() { return spec; }
}
