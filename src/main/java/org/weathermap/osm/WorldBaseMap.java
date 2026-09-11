package org.weathermap.osm;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A coarse world outline bundled with the application, for the area-selection
 * map at wide zoom.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Overpass cannot answer a continent-sized query - the one that returned
 * 14,889 features for a 3.5&deg; x 6&deg; box took nearly a minute, and a
 * hemisphere would be refused outright. So a map that the user can zoom all the
 * way out on cannot be fed from Overpass alone, and zooming out would otherwise
 * leave the panel blank at exactly the moment someone is trying to find the
 * region they want.</p>
 *
 * <p>Natural Earth 1:110m fills that gap: 5,128 coastline points and 3,108
 * boundary points, 68 KB on disk, public domain. It is far too coarse for a
 * composited output map - country outlines only, no detail below about 100 km -
 * but it is exactly right for "show me where I am pointing".</p>
 *
 * <p>{@link org.weathermap.gui.BaseMapLoader} layers the real OSM data over
 * this as soon as the view is small enough to query for.</p>
 *
 * <h2>The format</h2>
 *
 * <p>Written by {@code tools/make-world-basemap.py}; see that script for the
 * layout. Deliberately not a shapefile: reading one needs a parser, an index
 * and a {@code .dbf}, and none of that earns its place when all that is wanted
 * is a list of polylines.</p>
 */
public final class WorldBaseMap {

    private static final Logger LOG = Logger.getLogger(WorldBaseMap.class.getName());

    private static final String COASTLINE = "/basemap/world-coastline.bin";
    private static final String BOUNDARIES = "/basemap/world-boundaries.bin";

    private static final byte[] MAGIC = {'W', 'M', 'B', 'M'};

    /** Attribution for the bundled outline; the data itself is public domain. */
    public static final String ATTRIBUTION = "World outline: Natural Earth";

    private static List<Feature> cached;

    private WorldBaseMap() { }

    /**
     * The bundled coastline and boundaries, loaded once and shared.
     *
     * <p>Fails soft: if the resources are missing from the jar the selection map
     * simply has no world outline, which is a degraded map rather than a broken
     * application.</p>
     */
    public static synchronized List<Feature> features() {
        if (cached != null) return cached;

        final List<Feature> out = new ArrayList<>();
        out.addAll(read(COASTLINE, FeatureKind.COASTLINE, Map.of("natural", "coastline")));
        out.addAll(read(BOUNDARIES, FeatureKind.BOUNDARY,
                        Map.of("boundary", "administrative", "admin_level", "2")));
        LOG.fine(() -> "world outline: " + out.size() + " features");
        cached = List.copyOf(out);
        return cached;
    }

    private static List<Feature> read(String resource, FeatureKind kind,
                                      Map<String, String> tags) {
        final List<Feature> out = new ArrayList<>();
        try (InputStream in = WorldBaseMap.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOG.warning("Bundled world outline " + resource + " is missing");
                return out;
            }
            final DataInputStream data = new DataInputStream(new java.io.BufferedInputStream(in));

            final byte[] magic = new byte[4];
            data.readFully(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) {
                    LOG.warning(resource + " is not a world base map file");
                    return out;
                }
            }
            final int version = data.readInt();
            if (version != 1) {
                LOG.warning(resource + " is version " + version + ", expected 1");
                return out;
            }

            final int lineCount = data.readInt();
            for (int i = 0; i < lineCount; i++) {
                final int pointCount = data.readInt();
                final List<double[]> points = new ArrayList<>(pointCount);
                for (int p = 0; p < pointCount; p++) {
                    points.add(new double[]{data.readFloat(), data.readFloat()});
                }
                if (points.size() >= 2) out.add(new Feature(kind, points, tags));
            }
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read " + resource, e);
        }
        return out;
    }
}
