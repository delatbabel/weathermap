package org.weathermap.osm;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverpassClientTest {

    /**
     * The document that caused a month-long outage for an area: Overpass
     * reports a server-side timeout with HTTP 200 and a well-formed OSM file
     * whose only content is a remark. It parses fine and holds nothing.
     */
    private static final String TIMEOUT_REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <osm version="0.6" generator="Overpass API 0.7.62.11">
              <note>The data included in this document is from www.openstreetmap.org.</note>
              <meta osm_base="2026-09-12T04:38:36Z"/>
              <remark> runtime error: Query timed out in "query" at line 5 after 181 seconds. </remark>
            </osm>
            """;

    @Test
    void anErrorReportParsesToNothing() throws Exception {
        // Which is exactly why it has to be rejected before it reaches the
        // cache: nothing downstream can tell it apart from a genuinely empty
        // area.
        final List<Feature> features = new OsmXmlParser()
                .parse(new ByteArrayInputStream(TIMEOUT_REPORT.getBytes(StandardCharsets.UTF_8)));
        assertTrue(features.isEmpty());
    }

    @Test
    void asksForFewerPlaceTypesAsTheAreaGrows() {
        assertEquals("city", OverpassClient.placeTypesFor(BoundingBox.of(40, -20, 60, 20)));
        assertEquals("city|town", OverpassClient.placeTypesFor(BoundingBox.of(53, -8, 59, 1)));
        assertEquals("city|town|village",
                OverpassClient.placeTypesFor(BoundingBox.of(53, -2, 54, -1)));
        assertEquals("city|town|village|hamlet",
                OverpassClient.placeTypesFor(BoundingBox.of(53.0, -0.5, 53.5, 0.0)));
    }

    /**
     * The recursion that turns node references into coordinates is only needed
     * when ways were asked for. Emitting it for a places-only query makes the
     * server do real work for nothing.
     */
    @Test
    void onlyRecursesWhenWaysWereRequested() {
        final OverpassClient client = new OverpassClient();
        final BoundingBox bbox = BoundingBox.of(53, -8, 59, 1);

        assertTrue(client.buildQuery(bbox, List.of(FeatureKind.COASTLINE)).contains(">;"));
        assertTrue(!client.buildQuery(bbox, List.of(FeatureKind.PLACE)).contains(">;"),
                   "a places-only query needs no recursion");
    }
}
