package org.weathermap.render;

import org.weathermap.grib.Grid;
import org.weathermap.model.GribVariable;
import org.weathermap.model.MapProjection;
import org.weathermap.model.RenderSpec;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Draws a decoded {@link Grid} over the base map.
 *
 * <h2>Per output pixel, not per grid cell</h2>
 *
 * <p>The field is rendered by walking the <em>output</em> pixels, converting each
 * back to a latitude and longitude through the projection, and sampling the grid
 * there. The obvious alternative - drawing one filled rectangle per grid cell -
 * is faster but only correct under an equirectangular projection at exactly the
 * grid's resolution. Sampling per pixel is projection-agnostic, gives smooth
 * interpolation for free, and is fast enough: a 1600x1200 output is two million
 * bilinear samples, tens of milliseconds.</p>
 *
 * <p>The field is painted into its own {@link BufferedImage} and then composited
 * with {@link RenderSpec#gribOpacity()}, rather than drawn translucently pixel by
 * pixel, so that overlapping translucent cells cannot accumulate.</p>
 */
public final class GribLayer implements Layer {

    private final Grid grid;
    private final ColourRamp ramp;
    private final float opacity;

    public GribLayer(Grid grid, float opacity) {
        this(grid, ColourRamp.forVariable(grid.variable()), opacity);
    }

    public GribLayer(Grid grid, ColourRamp ramp, float opacity) {
        this.grid = grid;
        this.ramp = ramp;
        this.opacity = opacity;
    }

    public Grid grid() { return grid; }

    public ColourRamp ramp() { return ramp; }

    @Override
    public void draw(Graphics2D g, MapProjection projection) {
        final GribVariable.RenderStyle style = grid.variable().style();
        if (style == GribVariable.RenderStyle.FILLED_CONTOUR
                || style == GribVariable.RenderStyle.FILLED_AND_LINES) {
            drawFilled(g, projection);
        }
        if (style == GribVariable.RenderStyle.CONTOUR_LINES
                || style == GribVariable.RenderStyle.FILLED_AND_LINES) {
            drawContours(g, projection);
        }
        if (style == GribVariable.RenderStyle.VECTOR) {
            drawBarbs(g, projection);
        }
    }

    private void drawFilled(Graphics2D g, MapProjection projection) {
        final int w = projection.imageWidth();
        final int h = projection.imageHeight();
        final BufferedImage field = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final double[] latLon = projection.toLatLon(x + 0.5, y + 0.5);
                final float value = grid.sample(latLon[0], latLon[1]);
                if (Float.isNaN(value)) continue;            // leave transparent
                field.setRGB(x, y, ramp.colourFor(value).getRGB());
            }
        }

        final Composite saved = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
        g.drawImage(field, 0, 0, null);
        g.setComposite(saved);
    }

    /**
     * Isolines, for pressure and geopotential height.
     *
     * <p>TODO: implement marching squares over the sampled field, then label the
     * contours along their paths. This is the piece that makes a synoptic chart
     * look like one, and it is not hard - a 16-case lookup over each cell - but
     * it needs contour interval selection (whole hectopascals for MSLP, 60 gpm
     * at 500 mb) and label placement to be worth drawing.</p>
     */
    private void drawContours(Graphics2D g, MapProjection projection) {
        // Intentionally empty until marching squares exists.
    }

    /**
     * Wind barbs or arrows.
     *
     * <p>TODO: this needs the <em>paired</em> u and v components, which one
     * {@link Grid} does not carry - so the constructor has to take two grids for
     * this style, or {@link Compositor} has to pair them before building the
     * layer. Pairing in the compositor is the better seam: it already knows the
     * whole set of grids for one forecast hour.</p>
     */
    private void drawBarbs(Graphics2D g, MapProjection projection) {
        // Intentionally empty until u/v pairing exists.
    }

    @Override
    public RenderSpec.LayerKind kind() { return RenderSpec.LayerKind.GRIB; }
}
