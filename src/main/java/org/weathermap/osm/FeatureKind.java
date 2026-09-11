package org.weathermap.osm;

/**
 * The layers this application pulls out of OSM.
 *
 * <p>Kept to the three the base map needs. Adding a fourth means a query in
 * {@link OverpassClient}, a classification rule in {@link OsmXmlParser}, and a
 * {@link org.weathermap.render.Layer} to draw it - nothing else.</p>
 */
public enum FeatureKind {

    /**
     * {@code natural=coastline} ways. In OSM these are directed: land is on the
     * <em>left</em> of the way. That is what makes a land/sea fill possible
     * without a separate landmass polygon, and it is why direction must not be
     * normalised away when segments are stitched.
     */
    COASTLINE,

    /**
     * {@code boundary=administrative} ways. {@code admin_level=2} is national;
     * higher numbers are subdivisions and are filtered by the renderer rather
     * than by the query, so one download serves several styles.
     */
    BOUNDARY,

    /**
     * {@code place=city|town|village|hamlet} nodes - the label layer.
     */
    PLACE
}
