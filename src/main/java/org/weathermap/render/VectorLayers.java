package org.weathermap.render;

import org.weathermap.model.MapProjection;
import org.weathermap.model.RenderSpec;
import org.weathermap.osm.Feature;
import org.weathermap.osm.FeatureKind;
import org.weathermap.osm.WorldGazetteer;

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
import java.util.function.UnaryOperator;

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

    /**
     * Builds a pixel-space path from a feature's geographic points.
     *
     * <h2>Why the unwrapping</h2>
     *
     * <p>Longitude is cyclic and the projection has to cut the circle
     * somewhere: it puts the cut opposite the middle of the box, as far from
     * the view as it can be put. A way that steps over that cut - a coastline
     * on the far side of the world from the chart - comes back as one point at
     * the extreme left and the next at the extreme right, and the line between
     * them is drawn straight across the picture. A chart of the Indian Ocean
     * grew three horizontal streaks from South American coastline the moment
     * the projection learned to wrap.</p>
     *
     * <p>So each point after the first is taken in whichever turn of longitude
     * lies nearest the point before it. The path is then continuous whatever
     * it crosses, and a feature outside the box is drawn continuously outside
     * it instead of being torn across the middle. This assumes x is linear in
     * longitude, which is true of both projections here and of every cylindrical
     * one.</p>
     *
     * <h2>Why the thinning</h2>
     *
     * <p>OSM coastline is surveyed, not drawn for a screen. Thirteen degrees of
     * South-East Asia is a hundred megabytes of XML and 1.2 million points, and
     * across nine hundred pixels that is well over a thousand points per pixel
     * column - every one of them turned into a {@code lineTo} and handed to the
     * rasteriser to draw on top of the last. It measured at four hundred
     * milliseconds a render, on the event thread, <em>on every pan</em>.</p>
     *
     * <p>So a point within {@link #MIN_STEP_PX} of the last one kept is
     * dropped. Nothing is lost that could have been seen: the two would have
     * landed on the same pixel. The last point of a way is always kept, or a
     * line would stop short of where it ends.</p>
     */
    static Path2D.Double pathOf(Feature feature, MapProjection projection) {
        final double turn = 360.0 / projection.bounds().widthDegrees()
                * projection.imageWidth();
        final Path2D.Double path = new Path2D.Double();
        final List<double[]> points = feature.points();

        boolean first = true;
        double previousX = 0;
        double previousY = 0;
        for (int i = 0; i < points.size(); i++) {
            final double[] p = points.get(i);
            final Point2D.Double pt = projection.toPixel(p[0], p[1]);
            if (first) {
                path.moveTo(pt.x, pt.y);
                first = false;
                previousX = pt.x;
                previousY = pt.y;
                continue;
            }
            while (pt.x - previousX > turn / 2) pt.x -= turn;
            while (pt.x - previousX < -turn / 2) pt.x += turn;

            // The last point always goes in; anything else that has not moved
            // a pixel since the last one kept would draw over it.
            final boolean last = i == points.size() - 1;
            if (!last && Math.abs(pt.x - previousX) < MIN_STEP_PX
                    && Math.abs(pt.y - previousY) < MIN_STEP_PX) {
                continue;
            }
            path.lineTo(pt.x, pt.y);
            previousX = pt.x;
            previousY = pt.y;
        }
        return path;
    }

    /**
     * How far a point must be from the last one drawn to be worth drawing, in
     * pixels.
     *
     * <p>Just under one, so that anything dropped would have been rasterised
     * into a pixel already covered. Making it larger would start to flatten
     * real detail - a headland at this zoom is a pixel or two - and is not
     * where the win is: almost all of the saving is in the first pixel's
     * worth.</p>
     */
    static final double MIN_STEP_PX = 0.75;

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

        /** Cities, towns and so on, most important first. */
        private final List<Feature> places;

        /** Named water, most important first. */
        private final List<Feature> water;

        /** Country label points, most important first. */
        private final List<Feature> countries;

        private final Color dot = new Color(60, 60, 60);
        private final Color text = new Color(30, 30, 30);
        private final Color halo = new Color(255, 255, 255, 200);

        /** Water names in the colour of water, so they read as sea and not as land. */
        private final Color waterText = new Color(52, 92, 132);

        /** Country names sit back: they are the frame, not the subject. */
        private final Color countryText = new Color(105, 105, 112);

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

        /**
         * How small a body of water is worth naming at a given span.
         *
         * <p>Natural Earth's scale rank runs from 0 for an ocean through 1 for
         * the Bay of Bengal and 2 for the Gulf of Thailand to 4 for the Gulf of
         * Tonkin, so the cut runs the same way round as it does for places: a
         * wide chart wants the large features, and the small ones are clutter
         * on it. Narrow charts admit everything, because a body of water too
         * small to be in the set simply is not there to draw.</p>
         */
        private static int waterRankLimitFor(double spanDegrees) {
            if (spanDegrees > 40) return 2;
            if (spanDegrees > 15) return 4;
            return Integer.MAX_VALUE;
        }

        /**
         * Below this span, country names are left off.
         *
         * <p>Under about ten degrees the coastline shape and the city names
         * already say which country is which, and "Thailand" across a chart of
         * the upper Gulf is a word taking up room that a wind barb wanted.</p>
         */
        private static final double MIN_COUNTRY_SPAN = 10.0;

        /** Country label ranks above this are minor territories, not context. */
        private static final int COUNTRY_RANK_LIMIT = 5;

        private static int rankOf(Feature f) {
            final int i = IMPORTANCE.indexOf(String.valueOf(f.placeType()));
            return i < 0 ? IMPORTANCE.size() : i;
        }

        /**
         * What to place first when labels compete for the same space.
         *
         * <p>The two sources measure importance differently and cannot be
         * compared directly. An OSM place has a {@code place} tag and a real
         * population; a bundled one has Natural Earth's scale rank, which
         * already encodes the cartographer's judgement about when a name earns
         * its ink. They never appear together - the bundle is only reached for
         * when OSM has supplied nothing - so each list is simply sorted on its
         * own terms.</p>
         *
         * <p>Before this, every bundled city tagged itself {@code place=city}
         * and so ranked identically, and none of them carries a population tag:
         * the sort had nothing to work with and the survivors were whichever
         * happened to be tried first. That is how a chart of southern Asia
         * ended up naming Bhilai and Sholapur.</p>
         */
        private static int importanceOf(Feature f) {
            return WorldGazetteer.isBundled(f) ? WorldGazetteer.rankOf(f) : rankOf(f);
        }

        /** What to print instead of a feature's own name; see RenderSpec. */
        private java.util.function.UnaryOperator<String> naming = UnaryOperator.identity();

        public PlaceLabelLayer(List<Feature> features,
                               java.util.function.UnaryOperator<String> naming) {
            this(features);
            if (naming != null) this.naming = naming;
        }

        public PlaceLabelLayer(List<Feature> features) {
            this.places = new ArrayList<>(of(features, FeatureKind.PLACE));
            this.places.sort(Comparator
                    .comparingInt(PlaceLabelLayer::importanceOf)
                    .thenComparing(Comparator.comparingLong(Feature::population).reversed()));

            this.water = new ArrayList<>(of(features, FeatureKind.MARINE));
            this.water.sort(Comparator.comparingInt(WorldGazetteer::rankOf));

            this.countries = new ArrayList<>(of(features, FeatureKind.COUNTRY));
            this.countries.sort(Comparator.comparingInt(WorldGazetteer::rankOf));
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

        /**
         * How much larger place names are drawn than they first were.
         *
         * <p>They were sized for a window and read as captions on a 1600x1200
         * chart - unreadable on a phone, which is where most of these end up.
         * Everything about a label scales together: the text, the dot beside
         * it, the halo behind it and the space around it, because a doubled
         * name next to an unchanged dot looks like a mistake rather than a
         * choice.</p>
         */
        private static final float LABEL_SCALE = 2f;

        private static final float WATER_MAJOR_PT = 13f * LABEL_SCALE;
        private static final float WATER_MINOR_PT = 11.5f * LABEL_SCALE;
        private static final float COUNTRY_PT = 11f * LABEL_SCALE;
        private static final float PLACE_PT = 11f * LABEL_SCALE;

        /** Breathing room around a label, so names do not sit shoulder to shoulder. */
        private static final int LABEL_PADDING = 5;

        /** The dot a city name hangs off, and how far the name sits from it. */
        private static final int DOT_DIAMETER = Math.round(4 * LABEL_SCALE);
        private static final int DOT_GAP = Math.round(5 * LABEL_SCALE);

        /**
         * How far the halo reaches behind the text.
         *
         * <p>A one-pixel outline vanishes behind letters twice the size, so it
         * grows too - and every offset within the width is drawn, not just the
         * outermost ring, or the halo comes out hollow.</p>
         */
        private static final int HALO_WIDTH = Math.max(1, Math.round(LABEL_SCALE));

        /** One name waiting to be drawn, and how it should look. */
        private record Candidate(Feature feature, int importance, int kindOrder,
                                 float points, int style, Color colour, boolean dot) { }

        /**
         * Draws the names in one order, most important first, whatever kind
         * they are.
         *
         * <h2>Why they are not drawn kind by kind</h2>
         *
         * <p>They used to be: every sea, then every country, then every city.
         * That gave the seas first claim on the space, which is defensible on a
         * chart read for the wind and indefensible at the point where "Gulf of
         * Thailand" and "South China Sea" between them left nowhere to put Ho
         * Chi Minh City. A city of nine million losing its name to the water
         * beside it is not a trade anyone would choose.</p>
         *
         * <p>Rank settles it instead. All three kinds carry Natural Earth's
         * scale rank and it means the same thing in each, so they sort together:
         * an ocean still outranks a minor town, and a capital now outranks the
         * gulf it stands on. Where a city and a sea rank equally the city goes
         * first, which is exactly the case in hand - Ho Chi Minh City and the
         * Gulf of Thailand are both rank 2.</p>
         */
        @Override
        public void draw(Graphics2D g, MapProjection projection) {
            final double span = Math.max(projection.bounds().widthDegrees(),
                                         projection.bounds().heightDegrees());
            final int budget = labelBudget(projection);
            final Font base = g.getFont();

            final List<Candidate> candidates = new ArrayList<>();

            final int waterLimit = waterRankLimitFor(span);
            for (Feature f : water) {
                final int rank = WorldGazetteer.rankOf(f);
                if (rank > waterLimit) continue;
                // Italic, the cartographic convention for water, and sized by
                // importance so an ocean reads as larger than a gulf inside it.
                candidates.add(new Candidate(f, rank, 1,
                        rank <= 1 ? WATER_MAJOR_PT : WATER_MINOR_PT,
                        Font.ITALIC, waterText, false));
            }
            if (span >= MIN_COUNTRY_SPAN) {
                for (Feature f : countries) {
                    final int rank = WorldGazetteer.rankOf(f);
                    if (rank > COUNTRY_RANK_LIMIT) continue;
                    candidates.add(new Candidate(f, rank, 2,
                            COUNTRY_PT, Font.PLAIN, countryText, false));
                }
            }
            final int placeLimit = rankLimitFor(span);
            for (Feature f : places) {
                if (rankOf(f) > placeLimit) continue;
                candidates.add(new Candidate(f, importanceOf(f), 0,
                        PLACE_PT, Font.PLAIN, text, true));
            }

            candidates.sort(Comparator
                    .comparingInt(Candidate::importance)
                    .thenComparingInt(Candidate::kindOrder)
                    .thenComparing(c -> -c.feature().population()));

            // One collision set across all three kinds, because a sea name and a
            // city name overlapping is exactly as unreadable as two city names
            // overlapping, and separate layers cannot see each other to avoid it.
            final List<Rectangle2D> placed = new ArrayList<>();
            for (Candidate c : candidates) {
                if (placed.size() >= budget) break;
                g.setFont(base.deriveFont(c.style(), c.points()));
                place(g, projection, placed, c.feature(), c.colour(), c.dot());
            }
        }


        private static boolean overlapsAny(Rectangle2D box, List<Rectangle2D> placed) {
            for (Rectangle2D other : placed) {
                if (other.intersects(box)) return true;
            }
            return false;
        }

        /**
         * Places one label if it fits anywhere, and reports nothing if it does not.
         *
         * <p>A name with a dot is offset to the right of it; one without is
         * centred on its point, because a sea name marks an area rather than a
         * position and putting it beside an invisible dot looks like a mistake.</p>
         */
        private void place(Graphics2D g, MapProjection projection, List<Rectangle2D> placed,
                           Feature f, Color colour, boolean withDot) {
            final String name = naming.apply(f.name());
            if (name == null || name.isBlank()) return;

            final double[] p = f.points().get(0);
            final Point2D.Double pt = projection.toPixel(p[0], p[1]);
            if (pt.x < 0 || pt.y < 0
                    || pt.x > projection.imageWidth() || pt.y > projection.imageHeight()) {
                return;
            }

            final FontMetrics fm = g.getFontMetrics();
            final double width = fm.stringWidth(name);
            final double centred = pt.y + fm.getAscent() / 2.0 - 1;

            // Where the name may go, best first. A name on a dot has four sides
            // to try; a sea name is centred on its point because it marks an
            // area, and sliding it somewhere else would put it over the wrong
            // water. This is why the labels survive being twice the size they
            // were: at that size a city beside a sea name loses the space it
            // used to have, and Ho Chi Minh City and Hanoi both vanished under
            // "South China Sea" and "Vietnam" before there was anywhere else to
            // put them.
            final double[][] candidates = withDot
                    ? new double[][]{
                            {pt.x + DOT_GAP, centred},                        // right
                            {pt.x - DOT_GAP - width, centred},                // left
                            {pt.x - width / 2, pt.y - DOT_GAP},               // above
                            {pt.x - width / 2, pt.y + DOT_GAP + fm.getAscent()}}   // below
                    : new double[][]{{pt.x - width / 2, centred}};

            double tx = 0;
            double ty = 0;
            Rectangle2D box = null;
            for (double[] candidate : candidates) {
                final Rectangle2D tried = new Rectangle2D.Double(
                        candidate[0] - LABEL_PADDING,
                        candidate[1] - fm.getAscent() - LABEL_PADDING,
                        width + LABEL_PADDING * 2, fm.getHeight() + LABEL_PADDING * 2);

                // The point being on the page is not enough - a sea name is
                // centred on its point and runs both ways from it, so "East
                // China Sea" a few pixels inside the right edge was drawn as
                // "East China". Half a name is worse than no name: it reads as
                // a different place.
                if (tried.getMinX() < 0 || tried.getMinY() < 0
                        || tried.getMaxX() > projection.imageWidth()
                        || tried.getMaxY() > projection.imageHeight()) {
                    continue;
                }
                if (overlapsAny(tried, placed)) continue;

                tx = candidate[0];
                ty = candidate[1];
                box = tried;
                break;
            }
            if (box == null) return;
            placed.add(box);

            if (withDot) {
                g.setColor(dot);
                final int r = DOT_DIAMETER / 2;
                g.fillOval((int) pt.x - r, (int) pt.y - r, DOT_DIAMETER, DOT_DIAMETER);
            }

            // A halo keeps names legible over a busy GRIB field. Drawing the
            // string four times is crude next to a real outline but costs
            // nothing and needs no font-glyph work.
            g.setColor(halo);
            for (int dx = -HALO_WIDTH; dx <= HALO_WIDTH; dx++) {
                for (int dy = -HALO_WIDTH; dy <= HALO_WIDTH; dy++) {
                    if (dx != 0 || dy != 0) {
                        g.drawString(name, (float) tx + dx, (float) ty + dy);
                    }
                }
            }
            g.setColor(colour);
            g.drawString(name, (float) tx, (float) ty);
        }

        @Override
        public RenderSpec.LayerKind kind() { return RenderSpec.LayerKind.PLACE_LABELS; }
    }
}
