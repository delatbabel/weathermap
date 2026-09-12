package org.weathermap.render;

import org.weathermap.model.MapProjection;
import org.weathermap.model.RenderSpec;
import org.weathermap.osm.Feature;
import org.weathermap.osm.FeatureKind;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The layers drawn from OSM vector features: sea and land fill, coastline,
 * boundaries and place labels.
 *
 * <p>Grouped in one file because they share the geometry helpers and are
 * meaningless apart - all four read the same {@link Feature} list produced by
 * one Overpass query.</p>
 */
public final class VectorLayers {

    private VectorLayers() { }

    // ---- shared helpers --------------------------------------------------

    /** Builds a pixel-space path from a feature's geographic points. */
    static Path2D.Double pathOf(Feature feature, MapProjection projection) {
        final Path2D.Double path = new Path2D.Double();
        boolean first = true;
        for (double[] p : feature.points()) {
            final Point2D.Double pt = projection.toPixel(p[0], p[1]);
            if (first) {
                path.moveTo(pt.x, pt.y);
                first = false;
            }
            else {
                path.lineTo(pt.x, pt.y);
            }
        }
        return path;
    }

    static List<Feature> of(List<Feature> features, FeatureKind kind) {
        final List<Feature> out = new ArrayList<>();
        for (Feature f : features) {
            if (f.kind() == kind) out.add(f);
        }
        return out;
    }

    // ---- land and sea ----------------------------------------------------

    /**
     * Flat sea fill with land painted over it.
     *
     * <p><b>TODO: only the sea is painted.</b> Deriving land from the coastline
     * needs the OSM convention that land lies to the <em>left</em> of a
     * coastline way, plus stitching of the open segments a bbox query returns
     * into closed rings against the edges of the box. Until that exists this
     * lays down a sea-coloured base and
     * {@link CoastlineLayer} strokes the shore over it, which reads correctly
     * for coastal regions and wrongly for an inland box (all "sea").</p>
     */
    public static final class LandSeaLayer implements Layer {

        private final Color sea = new Color(198, 219, 239);
        private final Color land = new Color(247, 244, 236);
        private final boolean landDefault;

        /**
         * @param landDefault true to fill with land colour instead of sea - the
         *                    right choice for a box with no coast in it
         */
        public LandSeaLayer(boolean landDefault) {
            this.landDefault = landDefault;
        }

        @Override
        public void draw(Graphics2D g, MapProjection projection) {
            g.setColor(landDefault ? land : sea);
            g.fillRect(0, 0, projection.imageWidth(), projection.imageHeight());
        }

        @Override
        public RenderSpec.LayerKind kind() { return RenderSpec.LayerKind.LAND_SEA; }
    }

    // ---- coastline -------------------------------------------------------

    /** Strokes {@code natural=coastline} ways. */
    public static final class CoastlineLayer implements Layer {

        private final List<Feature> coastlines;
        private final Color colour = new Color(40, 70, 105);
        private final float width;

        public CoastlineLayer(List<Feature> features, float width) {
            this.coastlines = of(features, FeatureKind.COASTLINE);
            this.width = width;
        }

        @Override
        public void draw(Graphics2D g, MapProjection projection) {
            g.setColor(colour);
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (Feature f : coastlines) {
                g.draw(pathOf(f, projection));
            }
        }

        @Override
        public RenderSpec.LayerKind kind() { return RenderSpec.LayerKind.COASTLINE; }
    }

    // ---- boundaries ------------------------------------------------------

    /**
     * Strokes administrative boundaries, dashed, with national borders heavier
     * than subdivisions.
     */
    public static final class BoundaryLayer implements Layer {

        private final List<Feature> boundaries;
        private final int maxAdminLevel;
        private final Color colour = new Color(120, 90, 120);

        /**
         * @param maxAdminLevel 2 for national borders only; 4 also draws states
         *                      and provinces
         */
        public BoundaryLayer(List<Feature> features, int maxAdminLevel) {
            this.boundaries = of(features, FeatureKind.BOUNDARY);
            this.maxAdminLevel = maxAdminLevel;
        }

        @Override
        public void draw(Graphics2D g, MapProjection projection) {
            g.setColor(colour);
            for (Feature f : boundaries) {
                final int level = f.adminLevel();
                if (level < 2 || level > maxAdminLevel) continue;
                final float w = (level == 2) ? 1.6f : 0.9f;
                g.setStroke(new BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                            10f, new float[]{6f, 4f}, 0f));
                g.draw(pathOf(f, projection));
            }
        }

        @Override
        public RenderSpec.LayerKind kind() { return RenderSpec.LayerKind.BOUNDARIES; }
    }

    // ---- place labels ----------------------------------------------------

    /**
     * Draws city, town and village names, dropping the ones that would collide.
     *
     * <p>Labels are placed in importance order - {@code place=city} before
     * {@code town} before {@code village}, and within a class by population -
     * and a label is skipped when its box overlaps one already placed. That is
     * the cheap half of label placement; the expensive half, trying alternative
     * positions around the dot before giving up, is left as a TODO because the
     * greedy version is adequate at the scales this renders.</p>
     */
    public static final class PlaceLabelLayer implements Layer {

        private static final List<String> IMPORTANCE =
                List.of("city", "town", "village", "hamlet");

        private final List<Feature> places;
        private final Color dot = new Color(60, 60, 60);
        private final Color text = new Color(30, 30, 30);
        private final Color halo = new Color(255, 255, 255, 200);

        /**
         * The most detailed place type worth drawing at a given span.
         *
         * <p>An Overpass query for a few degrees returns every hamlet - nearly
         * fifteen thousand features for northern Britain in one test - and
         * drawing them all produces a wall of text with the map underneath. The
         * collision test alone does not help: it keeps whichever labels happen
         * to be placed first, so the map fills with villages and the cities are
         * crowded out.</p>
         *
         * <p>So the cut is made by importance before placement, against the size
         * of the area. The thresholds are judgement, not science, and are the
         * first thing to tune if the output looks wrong.</p>
         */
        private static int rankLimitFor(double spanDegrees) {
            if (spanDegrees > 10) return 0;      // cities only
            if (spanDegrees > 2) return 1;       // cities and towns
            if (spanDegrees > 0.75) return 2;    // ... and villages
            return IMPORTANCE.size();            // everything
        }

        private static int rankOf(Feature f) {
            final int i = IMPORTANCE.indexOf(String.valueOf(f.placeType()));
            return i < 0 ? IMPORTANCE.size() : i;
        }

        public PlaceLabelLayer(List<Feature> features) {
            this.places = new ArrayList<>(of(features, FeatureKind.PLACE));
            this.places.sort(Comparator
                    .comparingInt(PlaceLabelLayer::rankOf)
                    .thenComparing(Comparator.comparingLong(Feature::population).reversed()));
        }

        /**
         * How many labels the map can carry before they stop being labels.
         *
         * <p>Rank filtering alone is not enough. Nine degrees of Britain at
         * {@code place=town} is several hundred names, and drawing them all
         * buries the wind barbs - which inverts the whole point of the chart,
         * since the barbs are what it is for and the names are only there to say
         * where. Roughly one label per two thousand square pixels leaves the map
         * readable; the ones that survive are the most important, because the
         * list is already sorted that way.</p>
         */
        private static int labelBudget(MapProjection projection) {
            final long area = (long) projection.imageWidth() * projection.imageHeight();
            return (int) Math.max(12, Math.min(90, area / 22_000));
        }

        /** Breathing room around a label, so names do not sit shoulder to shoulder. */
        private static final int LABEL_PADDING = 5;

        @Override
        public void draw(Graphics2D g, MapProjection projection) {
            final double span = Math.max(projection.bounds().widthDegrees(),
                                         projection.bounds().heightDegrees());
            final int rankLimit = rankLimitFor(span);
            final int budget = labelBudget(projection);
            final List<Rectangle2D> placed = new ArrayList<>();
            final Font font = g.getFont().deriveFont(Font.PLAIN, 11f);
            g.setFont(font);
            final FontMetrics fm = g.getFontMetrics();

            for (Feature f : places) {
                if (placed.size() >= budget) break;
                if (rankOf(f) > rankLimit) continue;
                final String name = f.name();
                if (name == null || name.isBlank()) continue;

                final double[] p = f.points().get(0);
                final Point2D.Double pt = projection.toPixel(p[0], p[1]);
                if (pt.x < 0 || pt.y < 0
                        || pt.x > projection.imageWidth() || pt.y > projection.imageHeight()) {
                    continue;
                }

                final double tx = pt.x + 5;
                final double ty = pt.y + fm.getAscent() / 2.0 - 1;
                final Rectangle2D box = new Rectangle2D.Double(
                        tx - LABEL_PADDING, ty - fm.getAscent() - LABEL_PADDING,
                        fm.stringWidth(name) + LABEL_PADDING * 2,
                        fm.getHeight() + LABEL_PADDING * 2);

                boolean collides = false;
                for (Rectangle2D other : placed) {
                    if (other.intersects(box)) {
                        collides = true;
                        break;
                    }
                }
                // TODO: try the other three quadrants around the dot before
                // dropping the label.
                if (collides) continue;
                placed.add(box);

                g.setColor(dot);
                g.fillOval((int) pt.x - 2, (int) pt.y - 2, 4, 4);

                // A halo keeps names legible over a busy GRIB field. Drawing the
                // string four times is crude next to a real outline but costs
                // nothing and needs no font-glyph work.
                g.setColor(halo);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx != 0 || dy != 0) {
                            g.drawString(name, (float) tx + dx, (float) ty + dy);
                        }
                    }
                }
                g.setColor(text);
                g.drawString(name, (float) tx, (float) ty);
            }
        }

        @Override
        public RenderSpec.LayerKind kind() { return RenderSpec.LayerKind.PLACE_LABELS; }
    }
}
