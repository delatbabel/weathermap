package org.weathermap.osm;

/**
 * The layers the base map is made of.
 *
 * <p>Adding one means a query in {@link OverpassClient}, a classification rule
 * in {@link OsmXmlParser}, and a {@link org.weathermap.render.Layer} to draw it
 * - nothing else.</p>
 *
 * <p>The last two do not come from OSM at all. They are read from the bundled
 * gazetteer, because the Overpass place query never asked for water and could
 * not have answered it at continental scale anyway. See
 * {@link WorldGazetteer}.</p>
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
    PLACE,

    /**
     * A named body of water - sea, gulf, bay, strait or ocean - as a single
     * label point rather than an outline.
     *
     * <p>Never fetched from OSM. On a marine chart these are the names that
     * matter most, and they are the ones nothing else supplies: the wind a
     * reader is looking at blows across the Gulf of Thailand, not across an
     * unlabelled gap between two coastlines.</p>
     */
    MARINE,

    /**
     * A country name at its label point, for charts too wide for city names to
     * say where you are.
     */
    COUNTRY
}
