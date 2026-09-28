package org.weathermap.osm;

import org.weathermap.model.BoundingBox;

import java.io.IOException;
import java.util.List;

/**
 * Where base-map features come from.
 *
 * <p>An interface with one implementation today ({@link OverpassClient}) because
 * the alternative is real and may be needed: a {@code .osm.pbf} extract from
 * Geofabrik read off disk, which is the right answer for a large area or for
 * repeated offline use, where Overpass is neither fast enough nor polite to
 * ask.</p>
 */
public interface OsmSource {

    /**
     * Fetches every feature of the requested kinds inside {@code bbox}.
     *
     * @param kinds which layers to fetch; fetching only what is drawn keeps the
     *              query small, which matters on a rate-limited service
     * @return features in no particular order, possibly empty, never null
     */
    /** Features already held locally, and the area they actually cover. */
    record Cached(BoundingBox area, List<Feature> features) { }

    /**
     * What is already on disk for this area, without asking anyone.
     *
     * <p>Separate from {@link #fetch} because it answers a different
     * question. {@code fetch} asks "have I sent this exact query before",
     * which is keyed on a rectangle derived from the window size - so a
     * window one pixel different on the next run misses everything the last
     * one downloaded. This asks "have I already got this ground", which
     * survives a restart.</p>
     *
     * <p>The area returned is the one actually held, which may be larger than
     * the one asked about.</p>
     *
     * @return what is held, or null when nothing local covers it
     */
    default Cached cachedCovering(BoundingBox bbox, List<FeatureKind> kinds) {
        return null;
    }

    List<Feature> fetch(BoundingBox bbox, List<FeatureKind> kinds)
            throws IOException, InterruptedException;

    /** Human-readable, for progress messages and error reports. */
    String description();
}
