package org.weathermap.render;

import org.weathermap.grib.Grid;
import org.weathermap.model.MapProjection;
import org.weathermap.model.RenderSpec;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

/**
 * Wind barbs, drawn the way a marine weather chart draws them.
 *
 * <p>The top layer of the composite, and the reason these maps get made. Barbs
 * are the one element here that is read rather than glanced at, so everything
 * about this layer is arranged to keep them legible over whatever is
 * underneath: full opacity, a pale halo behind every stroke, and a station
 * spacing chosen so they never collide.</p>
 *
 * <h2>The convention</h2>
 *
 * <p>Reading a barb is unambiguous once you know the four rules, and getting
 * any of them wrong makes the map quietly lie:</p>
 *
 * <ul>
 *   <li>The <b>staff points into the wind</b> - along the direction the wind is
 *       coming <em>from</em>. A staff pointing north-east means a north-easterly,
 *       which blows toward the south-west.</li>
 *   <li>A <b>short tick</b> is 5 knots, a <b>long tick</b> 10, and a
 *       <b>filled triangle</b> 50. They are added up.</li>
 *   <li>Feathers sit at the <b>outer end</b> of the staff, furthest from the
 *       station, with pennants outermost, then long ticks, then any short one.</li>
 *   <li><b>Calm</b> - below 2.5 knots - is an open circle with no staff at all,
 *       not a bare staff, which would read as a direction nobody measured.</li>
 *   <li>Feathers sit on the side of the staff <b>toward low pressure</b>, which
 *       mirrors at the equator. Decided per station, so a chart spanning it is
 *       right on both sides.</li>
 * </ul>
 *
 * <p>Speed is rounded to the nearest 5 knots before decomposition, because the
 * notation cannot express anything finer and rounding afterwards produces
 * barbs that do not add up to their own label.</p>
 *
 * <h2>Where the numbers come from</h2>
 *
 * <p>GRIB carries wind as two scalar fields in metres per second: {@code UGRD}
 * eastward and {@code VGRD} northward. Speed is their magnitude; the
 * meteorological direction is {@code atan2(-u, -v)}, the double negation being
 * what turns "blowing toward" into "coming from".</p>
 */
public final class WindBarbLayer implements Layer {

    /** Below this, the wind is calm and gets a circle. */
    private static final double CALM_KNOTS = 2.5;

    private static final double MS_TO_KNOTS = 1.9438444924406;

    /** Length of the staff in pixels. */
    private static final double STAFF = 26;

    /** Length of a full (10 kt) tick. */
    private static final double TICK = 11;

    /** Gap along the staff between successive feathers. */
    private static final double FEATHER_GAP = 5.5;

    /** Angle a feather makes with the staff, leaning back toward the station. */
    private static final double FEATHER_ANGLE = Math.toRadians(115);

    /** Smallest gap between stations, in pixels. Keeps barbs from touching. */
    private static final int MIN_SPACING = 46;

    private final Grid uGrid;
    private final Grid vGrid;
    private final Color ink;
    private final Color halo;

    /**
     * @param uGrid the eastward component, m/s
     * @param vGrid the northward component, m/s
     */
    public WindBarbLayer(Grid uGrid, Grid vGrid) {
        this(uGrid, vGrid, new Color(15, 15, 25), new Color(255, 255, 255, 190));
    }

    public WindBarbLayer(Grid uGrid, Grid vGrid, Color ink, Color halo) {
        this.uGrid = uGrid;
        this.vGrid = vGrid;
        this.ink = ink;
        this.halo = halo;
    }

    public Grid uGrid() { return uGrid; }

    public Grid vGrid() { return vGrid; }

    @Override
    public RenderSpec.LayerKind kind() { return RenderSpec.LayerKind.WIND_BARBS; }

    @Override
    public void draw(Graphics2D g, MapProjection projection) {
        final int spacing = spacingFor(projection);

        // Half a step in from the edge, so a barb is never clipped in half.
        for (int y = spacing / 2; y < projection.imageHeight(); y += spacing) {
            for (int x = spacing / 2; x < projection.imageWidth(); x += spacing) {
                final double[] latLon = projection.toLatLon(x + 0.5, y + 0.5);
                final float u = uGrid.sample(latLon[0], latLon[1]);
                final float v = vGrid.sample(latLon[0], latLon[1]);
                if (Float.isNaN(u) || Float.isNaN(v)) continue;

                final double knots = Math.hypot(u, v) * MS_TO_KNOTS;
                final double fromRadians = Math.atan2(-u, -v);
                drawBarb(g, x, y, knots, fromRadians, latLon[0] >= 0);
            }
        }
    }

    /**
     * Station spacing.
     *
     * <p>Never finer than the data: drawing a barb every 46 pixels when a grid
     * cell covers 120 invents six readings that are all the same interpolated
     * value, which looks like information and is not.</p>
     */
    private int spacingFor(MapProjection projection) {
        final double pixelsPerCell =
                (double) projection.imageWidth() / Math.max(1, uGrid.width());
        return (int) Math.max(MIN_SPACING, Math.round(pixelsPerCell));
    }

    /**
     * One barb at a pixel position.
     *
     * @param knots       wind speed
     * @param fromRadians meteorological direction, 0 = from the north, measured
     *                    clockwise, as {@code atan2(-u, -v)} returns it
     */
    void drawBarb(Graphics2D g, double x, double y, double knots, double fromRadians) {
        drawBarb(g, x, y, knots, fromRadians, true);
    }

    void drawBarb(Graphics2D g, double x, double y, double knots, double fromRadians,
                  boolean northernHemisphere) {
        final Path2D.Double shape = barbShape(x, y, knots, fromRadians, northernHemisphere);
        final boolean calm = knots < CALM_KNOTS;

        // Drawn twice: a fat pale stroke first, so a barb stays readable over a
        // dark precipitation patch or a coastline, then the barb itself.
        g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(halo);
        g.draw(shape);

        g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(ink);
        g.draw(shape);
        if (!calm) g.fill(pennants(x, y, knots, fromRadians, northernHemisphere));
    }

    /**
     * The side the feathers are drawn on.
     *
     * <p>They point toward low pressure, which Buys Ballot's law puts on the
     * left of an observer with their back to the wind in the northern
     * hemisphere and on the right in the southern. So the convention mirrors at
     * the equator, and a chart that ignores that is drawing southern winds with
     * the feathers on the wrong side of the staff - the speed still reads
     * correctly, but it looks wrong to anyone who plots them.</p>
     *
     * <p>Decided per station rather than per map, so a chart spanning the
     * equator is right on both sides of it.</p>
     */
    private static double featherAngle(boolean northernHemisphere) {
        return northernHemisphere ? FEATHER_ANGLE : -FEATHER_ANGLE;
    }

    /** The staff, ticks and pennant outlines as one path. */
    private Path2D.Double barbShape(double x, double y, double knots, double fromRadians,
                                    boolean northernHemisphere) {
        final Path2D.Double path = new Path2D.Double();

        if (knots < CALM_KNOTS) {
            final double r = 3.5;
            path.append(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2), false);
            return path;
        }

        // Unit vector along the staff, pointing the way the wind comes from.
        // Screen y grows downward, which is why the north component is negated.
        final double sx = Math.sin(fromRadians);
        final double sy = -Math.cos(fromRadians);

        final double endX = x + sx * STAFF;
        final double endY = y + sy * STAFF;
        path.append(new Line2D.Double(x, y, endX, endY), false);

        final int[] counts = decompose(knots);
        final int pennantCount = counts[0];
        final int longCount = counts[1];
        final int shortCount = counts[2];

        // Feathers march inward from the outer end. Pennants take two gaps
        // each, since a triangle needs a base along the staff.
        double along = 0;
        for (int i = 0; i < pennantCount; i++) along += FEATHER_GAP * 2;
        final double angle = featherAngle(northernHemisphere);
        for (int i = 0; i < longCount; i++) {
            addTick(path, endX, endY, sx, sy, along, 1.0, angle);
            along += FEATHER_GAP;
        }
        if (shortCount > 0) {
            // A lone half-barb sits one place in from the end, so it cannot be
            // mistaken for a full tick that happens to be drawn short.
            if (pennantCount == 0 && longCount == 0) along += FEATHER_GAP;
            addTick(path, endX, endY, sx, sy, along, 0.5, angle);
        }
        return path;
    }

    /** The filled triangles, which have to be a separate path so they can be filled. */
    private Path2D.Double pennants(double x, double y, double knots, double fromRadians,
                                   boolean northernHemisphere) {
        final Path2D.Double path = new Path2D.Double();
        final int pennantCount = decompose(knots)[0];
        if (pennantCount == 0) return path;

        final double sx = Math.sin(fromRadians);
        final double sy = -Math.cos(fromRadians);
        final double endX = x + sx * STAFF;
        final double endY = y + sy * STAFF;

        final double angle = featherAngle(northernHemisphere);
        final double fx = Math.cos(angle) * sx - Math.sin(angle) * sy;
        final double fy = Math.sin(angle) * sx + Math.cos(angle) * sy;

        for (int i = 0; i < pennantCount; i++) {
            final double base = i * FEATHER_GAP * 2;
            final double baseX = endX - sx * base;
            final double baseY = endY - sy * base;
            final double innerX = endX - sx * (base + FEATHER_GAP * 2);
            final double innerY = endY - sy * (base + FEATHER_GAP * 2);

            path.moveTo(baseX, baseY);
            path.lineTo(baseX + fx * TICK, baseY + fy * TICK);
            path.lineTo(innerX, innerY);
            path.closePath();
        }
        return path;
    }

    /** One tick, {@code along} pixels in from the staff end. */
    private void addTick(Path2D.Double path, double endX, double endY,
                         double sx, double sy, double along, double lengthScale,
                         double angle) {
        final double baseX = endX - sx * along;
        final double baseY = endY - sy * along;

        // The feather leans back toward the station, which is what makes a barb
        // read as an arrow rather than a cross.
        final double fx = Math.cos(angle) * sx - Math.sin(angle) * sy;
        final double fy = Math.sin(angle) * sx + Math.cos(angle) * sy;

        path.append(new Line2D.Double(baseX, baseY,
                                      baseX + fx * TICK * lengthScale,
                                      baseY + fy * TICK * lengthScale), false);
    }

    /**
     * Splits a speed into pennants, full ticks and a half tick.
     *
     * @return {@code {50s, 10s, 5s}} - the last is 0 or 1
     */
    static int[] decompose(double knots) {
        int rounded = (int) (Math.round(knots / 5.0) * 5);
        final int pennants = rounded / 50;
        rounded -= pennants * 50;
        final int longs = rounded / 10;
        rounded -= longs * 10;
        return new int[]{pennants, longs, rounded >= 5 ? 1 : 0};
    }

    /** Meteorological direction in degrees, for labels and tests. */
    public static double directionFrom(double u, double v) {
        final double degrees = Math.toDegrees(Math.atan2(-u, -v));
        return (degrees + 360) % 360;
    }

    /** Speed in knots, for labels and tests. */
    public static double knots(double u, double v) {
        return Math.hypot(u, v) * MS_TO_KNOTS;
    }
}
