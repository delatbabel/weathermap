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

    /**
     * Paints the wash.
     *
     * <p>Only the wash. This class once dispatched on
     * {@link GribVariable.RenderStyle} and carried empty methods for the other
     * two, which meant a variable styled as lines or as vectors was given a
     * layer that drew nothing at all - pressure was silently absent from every
     * chart that asked for it. Those styles now have layers of their own,
     * {@link IsolineLayer} and {@link WindBarbLayer}, and {@link Compositor}
     * decides which a field gets. A field that is not washed does not get this
     * one.</p>
     */
    @Override
    public void draw(Graphics2D g, MapProjection projection) {
        drawFilled(g, projection);
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

    @Override
    public RenderSpec.LayerKind kind() { return RenderSpec.LayerKind.GRIB; }
}
