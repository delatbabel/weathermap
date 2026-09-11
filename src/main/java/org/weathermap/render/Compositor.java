package org.weathermap.render;

import org.weathermap.grib.Grid;
import org.weathermap.model.BoundingBox;
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

        // TODO: decide land-versus-sea default from whether the box contains any
        // coastline rather than assuming land. See VectorLayers.LandSeaLayer.
        layers.add(new VectorLayers.LandSeaLayer(true));
        layers.add(new VectorLayers.CoastlineLayer(features, 1.2f));
        layers.add(new VectorLayers.BoundaryLayer(features, 2));

        Grid primary = null;
        ColourRamp primaryRamp = null;
        for (Grid grid : grids) {
            // TODO: pair UGRD with VGRD into a single vector layer instead of
            // drawing each component separately - see GribLayer#drawBarbs.
            final ColourRamp ramp = rampFor(grid);
            layers.add(new GribLayer(grid, ramp, spec.gribOpacity()));
            if (primary == null) {
                primary = grid;
                primaryRamp = ramp;
            }
        }

        layers.add(new GraticuleLayer());
        layers.add(new VectorLayers.PlaceLabelLayer(features));
        layers.add(new AnnotationLayer(primary, primaryRamp, modelName));
        return layers;
    }

    /**
     * The ramp one field is drawn with.
     *
     * <p>Built here rather than inside {@link GribLayer} so that the legend and
     * the field cannot disagree: {@link AnnotationLayer} is handed the same
     * instance, so whatever range is used is the range that is labelled.</p>
     */
    private ColourRamp rampFor(Grid grid) {
        final ColourRamp ramp = ColourRamp.forVariable(grid.variable());
        if (!spec.autoScaleRamp() || ramp.isAbsolute()) return ramp;
        final float[] range = grid.range();
        return ramp.fittedTo(range[0], range[1]);
    }

    public RenderSpec spec() { return spec; }
}
