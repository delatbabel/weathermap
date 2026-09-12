package org.weathermap.osm;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Names for the bundled world outline: cities, countries, and the sea.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link WorldBaseMap} gives a chart its shapes and nothing else. Above the
 * span Overpass will serve, that left a composited map with no labels on it
 * whatsoever - a 56&deg; chart of South-East Asia came out as unnamed outlines
 * and several hundred wind barbs, readable only by someone who could already
 * identify Sumatra by its shape.</p>
 *
 * <p>The water names matter most and were missing at <em>every</em> scale, not
 * just the wide ones, because the Overpass query asks for
 * {@code place=city|town|...} and never asked for water at all. On a marine
 * chart that is the wrong thing to omit: the wind being read blows out of the
 * Bay of Bengal or across the Gulf of Thailand, and those two answers imply
 * different weather in the same place.</p>
 *
 * <h2>What is in it</h2>
 *
 * <p>1,128 cities and 118 marine features from Natural Earth 1:50m, and 177
 * country label points from 1:110m - 29 KB, public domain. The marine set is at
 * 1:50m because 1:110m carries 29 features and stops at "South China Sea",
 * which is one useful name short of the example that prompted this.</p>
 *
 * <p>Everything is bundled and the renderer cuts by span, rather than the other
 * way round: what belongs on a chart depends on how wide it is, and that is not
 * knowable here.</p>
 *
 * <p>Written by {@code tools/make-gazetteer.py}; see that script for the
 * layout.</p>
 */
public final class WorldGazetteer {

    private static final Logger LOG = Logger.getLogger(WorldGazetteer.class.getName());

    private static final String RESOURCE = "/basemap/world-gazetteer.bin";
    private static final byte[] MAGIC = {'W', 'M', 'G', 'Z'};

    private static final int KIND_CITY = 0;
    private static final int KIND_MARINE = 1;
    private static final int KIND_COUNTRY = 2;

    /** Attribution for the bundled names; the data itself is public domain. */
    public static final String ATTRIBUTION = "Place names: Natural Earth";

    /**
     * The tag every gazetteer entry carries, so a renderer can tell a bundled
     * name from one that came out of OSM.
     *
     * <p>They are not interchangeable. An OSM place has a real population and a
     * real {@code place} type; a gazetteer entry has a cartographic rank, which
     * is a judgement about when a name is worth drawing rather than a fact about
     * the place.</p>
     */
    public static final String SOURCE_TAG = "source";
    public static final String SOURCE = "naturalearth";

    /** The rank tag: 0 is the most important, and it is a rendering hint. */
    public static final String RANK_TAG = "rank";

    private static List<Feature> cached;

    private WorldGazetteer() { }

    /**
     * Every bundled name, loaded once and shared.
     *
     * <p>Fails soft: a missing or unreadable resource costs the chart its
     * labels, which is a poorer map rather than a broken application.</p>
     */
    public static synchronized List<Feature> features() {
        if (cached != null) return cached;
        cached = List.copyOf(read());
        LOG.fine(() -> "gazetteer: " + cached.size() + " names");
        return cached;
    }

    /** Just the entries of one kind, in rank order. */
    public static List<Feature> of(FeatureKind kind) {
        final List<Feature> out = new ArrayList<>();
        for (Feature f : features()) {
            if (f.kind() == kind) out.add(f);
        }
        return out;
    }

    /** The cartographic rank of a gazetteer entry, or {@link Integer#MAX_VALUE}. */
    public static int rankOf(Feature feature) {
        final String s = feature.tags().get(RANK_TAG);
        if (s == null) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** True for a name that came from here rather than from OSM. */
    public static boolean isBundled(Feature feature) {
        return SOURCE.equals(feature.tags().get(SOURCE_TAG));
    }

    private static List<Feature> read() {
        final List<Feature> out = new ArrayList<>();
        try (InputStream in = WorldGazetteer.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOG.warning("Bundled gazetteer " + RESOURCE + " is missing; "
                        + "wide-area charts will have no labels");
                return out;
            }
            final DataInputStream data = new DataInputStream(new BufferedInputStream(in));

            final byte[] magic = new byte[4];
            data.readFully(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) {
                    LOG.warning(RESOURCE + " is not a gazetteer file");
                    return out;
                }
            }
            final int version = data.readInt();
            if (version != 1) {
                LOG.warning(RESOURCE + " is version " + version + ", expected 1");
                return out;
            }

            final int count = data.readInt();
            for (int i = 0; i < count; i++) {
                final int kind = data.readUnsignedByte();
                final int rank = data.readUnsignedByte();
                final double lat = data.readFloat();
                final double lon = data.readFloat();
                final byte[] name = new byte[data.readShort()];
                data.readFully(name);

                final Feature feature = toFeature(
                        kind, rank, lat, lon, new String(name, StandardCharsets.UTF_8));
                if (feature != null) out.add(feature);
            }
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read " + RESOURCE, e);
        }
        return out;
    }

    private static Feature toFeature(int kind, int rank, double lat, double lon, String name) {
        final Map<String, String> tags = new HashMap<>();
        tags.put("name", name);
        tags.put(SOURCE_TAG, SOURCE);
        tags.put(RANK_TAG, Integer.toString(rank));

        final FeatureKind featureKind = switch (kind) {
            case KIND_CITY -> {
                // Bundled cities are all sizeable, so they enter the existing
                // place layer as cities - the rank, not the tag, is what decides
                // whether one is drawn.
                tags.put("place", "city");
                yield FeatureKind.PLACE;
            }
            case KIND_MARINE -> {
                tags.put("place", "sea");
                yield FeatureKind.MARINE;
            }
            case KIND_COUNTRY -> {
                tags.put("place", "country");
                yield FeatureKind.COUNTRY;
            }
            default -> null;
        };
        if (featureKind == null) {
            LOG.fine(() -> "gazetteer: skipping unknown kind " + kind + " for " + name);
            return null;
        }
        return new Feature(featureKind, List.of(new double[]{lat, lon}), tags);
    }
}
