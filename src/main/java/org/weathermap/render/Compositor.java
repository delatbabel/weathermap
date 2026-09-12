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
     * @param grids     decoded fields for one forecast hour; the first drives the
     *                  legend and the title
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

        Grid primary = null;
        ColourRamp primaryRamp = null;
        Grid uGrid = null;
        Grid vGrid = null;

        for (Grid grid : grids) {
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

            final FieldStyle style = FieldStyle.forGrid(grid, spec.autoScaleRamp());
            layers.add(new GribLayer(grid, style.ramp(),
                                     style.opacity() * spec.gribOpacity()));
            if (primary == null) {
                primary = grid;
                primaryRamp = style.ramp();
            }
        }

        // --- middle: the base map, over the fields --------------------------
        layers.add(new GraticuleLayer());
        layers.add(new VectorLayers.CoastlineLayer(features, 1.4f));
        layers.add(new VectorLayers.BoundaryLayer(features, 2));
        layers.add(new VectorLayers.PlaceLabelLayer(features));

        // --- top: the wind ---------------------------------------------------
        if (uGrid != null && vGrid != null) {
            layers.add(new WindBarbLayer(uGrid, vGrid));
            if (primary == null) primary = uGrid;      // so the title names something
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
        layers.add(new AnnotationLayer(primary, primaryRamp, modelName, hasWind));
        return layers;
    }

    public RenderSpec spec() { return spec; }
}
