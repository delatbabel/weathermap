package org.weathermap.render;

import org.weathermap.model.MapProjection;

import java.awt.Graphics2D;

/**
 * One drawable band of the composited map.
 *
 * <p>Layers are drawn bottom to top into a single image, each one given the same
 * {@link MapProjection} - which is the whole registration mechanism. A layer
 * never decides where it sits in the stack or how large the image is; that is
 * {@link Compositor}'s job, driven by
 * {@link org.weathermap.model.RenderSpec.LayerKind}.</p>
 *
 * <p>Implementations must be safe to draw more than once (the UI re-renders on
 * every pan) and must not mutate the {@link Graphics2D} state they are handed
 * without restoring it - {@link Compositor} passes each layer its own scratch
 * context, but clip and composite changes still leak through a shared image.</p>
 */
public interface Layer {

    /**
     * Draws this layer.
     *
     * @param g          a context sized to the projection's image, origin top-left
     * @param projection maps geography to the pixels of that image
     */
    void draw(Graphics2D g, MapProjection projection);

    /** Which slot in the stack this layer occupies. */
    org.weathermap.model.RenderSpec.LayerKind kind();
}
