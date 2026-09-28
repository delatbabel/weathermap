package org.weathermap.osm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.weathermap.model.BoundingBox;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parsed OSM features, written to disk so they need not be parsed again.
 *
 * <h2>Why</h2>
 *
 * <p>The Overpass response is already cached, but as the XML it arrived as,
 * and turning that back into features is not cheap: a hundred and eight
 * megabytes of South-East Asian coastline takes about three seconds, on every
 * press of <b>Load detail</b> and again after every restart. The bytes were
 * cached and the work was not.</p>
 *
 * <p>So the features are written alongside, in a form built for reading back:
 * the same hundred and eight megabytes becomes about ten, and loads in a
 * fraction of the time. The XML stays as the canonical copy, because it is
 * what the server actually said and this file is only a derivation of it -
 * if the format changes or the parser improves, this is thrown away and
 * rebuilt while the response itself is untouched.</p>
 *
 * <h2>The format</h2>
 *
 * <pre>
 *   magic     "WMFC"                       4 bytes
 *   version                                int
 *   area      south, west, north, east     4 x int, microdegrees
 *   kinds     count byte, then ordinals    what was asked for
 *   places    UTF                          the place= values asked for, or ""
 *   strings   count, then count x UTF      the tag keys and values, pooled
 *   features  count                        int
 *   per feature:
 *       kind        byte      ordinal of {@link FeatureKind}
 *       tags        short, then that many (int key, int value) into the pool
 *       points      int, then that many (int lat, int lon) in microdegrees
 * </pre>
 *
 * <p>Microdegrees rather than floats: a tenth of a metre, which is finer than
 * OSM is surveyed, in the same four bytes a float would take and without the
 * quiet loss of precision one would bring at 180 degrees. It is the same
 * convention GRIB2 uses for its own angles, decoded a few packages away.</p>
 *
 * <p>Tag keys and values are pooled because they repeat: six thousand
 * coastline ways carry the same two strings between them, and writing those
 * out per feature is most of what is left after the geometry.</p>
 *
 * <p><b>Fails soft in both directions.</b> A file that cannot be written is
 * logged and forgotten - the features are in hand either way - and one that
 * cannot be read returns null, which the caller answers by parsing the XML
 * again. There is no case where a bad cache file is worse than no cache
 * file.</p>
 */
public final class FeatureStore {

    private static final Logger LOG = Logger.getLogger(FeatureStore.class.getName());

    private static final byte[] MAGIC = {'W', 'M', 'F', 'C'};

    /**
     * Bumped whenever the format or the parser changes what it produces.
     *
     * <p>An older file is then ignored and rebuilt rather than read as if it
     * said something it does not. This is why the XML is kept: there is
     * always something to rebuild from.</p>
     *
     * <p>Version 2 added the header: the area the extract covers, the kinds
     * in it, and how fine the place query was. Without those an entry could
     * only be found again by an exact match on the request that produced it,
     * which meant a window one pixel different on the next run missed
     * everything it had downloaded on the last.</p>
     */
    private static final int VERSION = 2;

    /** Degrees to the stored integer unit. */
    private static final double SCALE = 1e6;

    private FeatureStore() { }

    private static int microDegrees(double value) {
        return (int) Math.round(value * SCALE);
    }

    private static double degrees(int microDegrees) {
        return microDegrees / SCALE;
    }

    /**
     * What a stored extract says about itself, without reading the features.
     *
     * <p>Enough to answer "have I already got this?": where it covers, what
     * is in it, and how fine the place query behind it was. Reading it costs
     * a few dozen bytes, so a whole cache directory can be searched in the
     * time one entry takes to load.</p>
     *
     * @param placeTypes the {@code place=} values that were asked for, as
     *                   {@code OverpassClient.placeTypesFor} writes them;
     *                   empty when places were not asked for at all
     */
    public record Header(BoundingBox area, List<FeatureKind> kinds, String placeTypes) {

        public Header {
            kinds = List.copyOf(kinds);
        }

        /**
         * True when this extract can stand in for a request for {@code area}
         * and {@code wanted} at {@code wantedPlaceTypes}.
         *
         * <p>Covering the ground is not enough on its own. Overpass is asked
         * for fewer {@code place=} values as the area grows - hamlets over
         * four degrees are thousands of nodes the renderer then throws away -
         * so a wide extract genuinely holds fewer names than a narrow one,
         * and handing it back for a close-in request would quietly lose every
         * village.</p>
         */
        public boolean answers(BoundingBox wantedArea, List<FeatureKind> wanted,
                               String wantedPlaceTypes) {
            if (!area.contains(wantedArea)) return false;
            if (!kinds.containsAll(wanted)) return false;
            return placeTypeSet(placeTypes).containsAll(placeTypeSet(wantedPlaceTypes));
        }

        private static java.util.Set<String> placeTypeSet(String types) {
            if (types == null || types.isBlank()) return java.util.Set.of();
            return new java.util.HashSet<>(List.of(types.split("\\|")));
        }
    }

    /** A stored extract: what it covers, and what is in it. */
    public record Extract(Header header, List<Feature> features) {

        public Extract {
            features = List.copyOf(features);
        }

        public BoundingBox area() { return header.area(); }
    }

    // ---- writing ----------------------------------------------------------

    /**
     * Writes an extract to {@code target}, through a {@code .part} file so an
     * interrupted write never leaves something that looks readable.
     *
     * @return true when it was written
     */
    public static boolean write(Extract extract, Path target) {
        final List<Feature> features = extract.features();
        final Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            final Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);

            // The pool is built first so its size is known before anything
            // referring into it is written.
            final Map<String, Integer> pool = new LinkedHashMap<>();
            for (Feature f : features) {
                for (Map.Entry<String, String> tag : f.tags().entrySet()) {
                    pool.putIfAbsent(tag.getKey(), pool.size());
                    pool.putIfAbsent(tag.getValue(), pool.size());
                }
            }

            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(part), 1 << 16))) {
                out.write(MAGIC);
                out.writeInt(VERSION);

                final Header header = extract.header();
                out.writeInt(microDegrees(header.area().south()));
                out.writeInt(microDegrees(header.area().west()));
                out.writeInt(microDegrees(header.area().north()));
                out.writeInt(microDegrees(header.area().east()));
                out.writeByte(header.kinds().size());
                for (FeatureKind kind : header.kinds()) out.writeByte(kind.ordinal());
                out.writeUTF(header.placeTypes() == null ? "" : header.placeTypes());

                out.writeInt(pool.size());
                for (String s : pool.keySet()) out.writeUTF(s);

                out.writeInt(features.size());
                for (Feature f : features) {
                    out.writeByte(f.kind().ordinal());

                    out.writeShort(f.tags().size());
                    for (Map.Entry<String, String> tag : f.tags().entrySet()) {
                        out.writeInt(pool.get(tag.getKey()));
                        out.writeInt(pool.get(tag.getValue()));
                    }

                    out.writeInt(f.points().size());
                    for (double[] p : f.points()) {
                        out.writeInt(microDegrees(p[0]));
                        out.writeInt(microDegrees(p[1]));
                    }
                }
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
        catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not write parsed features to " + target, e);
            try {
                Files.deleteIfExists(part);
            }
            catch (IOException ignored) {
                // Nothing useful to do; the entry is already being given up on.
            }
            return false;
        }
    }

    // ---- reading ----------------------------------------------------------

    /**
     * Reads only what an extract says about itself.
     *
     * <p>A few dozen bytes rather than ten megabytes, so every entry in a
     * cache directory can be looked at to find the one that answers a
     * question.</p>
     *
     * @return the header, or null for anything wrong with the file
     */
    public static Header readHeader(Path source) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(source), 256))) {
            return readHeaderFrom(in);
        }
        catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Ignoring an unreadable feature cache at " + source, e);
            return null;
        }
    }

    private static Header readHeaderFrom(DataInputStream in) throws IOException {
        final byte[] magic = new byte[4];
        in.readFully(magic);
        for (int i = 0; i < MAGIC.length; i++) {
            if (magic[i] != MAGIC[i]) return null;
        }
        if (in.readInt() != VERSION) return null;

        final BoundingBox area;
        try {
            area = BoundingBox.of(degrees(in.readInt()), degrees(in.readInt()),
                                  degrees(in.readInt()), degrees(in.readInt()));
        }
        catch (IllegalArgumentException e) {
            // A corrupt header reads as a nonsensical rectangle rather than as
            // a short file, so it has to be caught here as well.
            return null;
        }

        final FeatureKind[] all = FeatureKind.values();
        final int kindCount = in.readByte();
        if (kindCount < 0 || kindCount > all.length) return null;
        final List<FeatureKind> kinds = new ArrayList<>(kindCount);
        for (int i = 0; i < kindCount; i++) {
            final int ordinal = in.readByte();
            if (ordinal < 0 || ordinal >= all.length) return null;
            kinds.add(all[ordinal]);
        }
        return new Header(area, kinds, in.readUTF());
    }

    /**
     * Reads an extract written by {@link #write}.
     *
     * @return the features, or null for anything at all wrong with the file -
     *         missing, truncated, written by a different version, or holding
     *         a kind this build does not have
     */
    public static Extract read(Path source) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(source), 1 << 16))) {

            final Header header = readHeaderFrom(in);
            if (header == null) return null;

            final String[] pool = new String[in.readInt()];
            for (int i = 0; i < pool.length; i++) pool[i] = in.readUTF();

            final FeatureKind[] kinds = FeatureKind.values();
            final int count = in.readInt();
            final List<Feature> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final int kind = in.readByte();
                if (kind < 0 || kind >= kinds.length) return null;

                final int tagCount = in.readShort();
                final Map<String, String> tags = new HashMap<>(Math.max(4, tagCount * 2));
                for (int t = 0; t < tagCount; t++) {
                    final String key = pool[in.readInt()];
                    tags.put(key, pool[in.readInt()]);
                }

                final int pointCount = in.readInt();
                final List<double[]> points = new ArrayList<>(pointCount);
                for (int p = 0; p < pointCount; p++) {
                    points.add(new double[]{in.readInt() / SCALE, in.readInt() / SCALE});
                }
                out.add(new Feature(kinds[kind], points, tags));
            }
            return new Extract(header, out);
        }
        catch (IOException | RuntimeException e) {
            // Including a truncated file, which shows up as an EOF or an array
            // index rather than as anything more specific.
            LOG.log(Level.FINE, "Ignoring an unreadable feature cache at " + source, e);
            return null;
        }
    }
}
