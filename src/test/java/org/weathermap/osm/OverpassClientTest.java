package org.weathermap.osm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weathermap.model.BoundingBox;
import org.weathermap.util.Cache;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * Overpass's {@code bbox} filter has no way to say "through 180", so a
     * crossing area is asked for as two filters in the same union - one
     * request, one cache entry, and every feature either side of the seam.
     */
    @Test
    void asksForBothSidesOfTheAntimeridianInOneQuery() {
        final String query = new OverpassClient().buildQuery(
                BoundingBox.of(-10, 170, 10, -170), List.of(FeatureKind.COASTLINE));

        assertTrue(query.contains("(-10.00000,170.00000,10.00000,180.00000)"), query);
        assertTrue(query.contains("(-10.00000,-180.00000,10.00000,-170.00000)"), query);
        assertEquals(2, query.split("natural", -1).length - 1,
                     "one coastline clause per side");
    }

    /** An area that does not cross still makes exactly one clause per kind. */
    @Test
    void anOrdinaryAreaIsStillOneClausePerKind() {
        final String query = new OverpassClient().buildQuery(
                BoundingBox.of(49.5, -11, 61, 2),
                List.of(FeatureKind.COASTLINE, FeatureKind.PLACE));

        assertEquals(1, query.split("natural", -1).length - 1);
        assertEquals(1, query.split("place", -1).length - 1);
    }

    // ---- the parsed form beside the response --------------------------------

    /** A stand-in Overpass that answers once with a fixed document. */
    private static com.sun.net.httpserver.HttpServer serving(
            String body, java.util.concurrent.atomic.AtomicInteger hits) throws Exception {
        final var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            final byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (var os = exchange.getResponseBody()) { os.write(out); }
        });
        server.start();
        return server;
    }

    private static final String TWO_PLACES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <osm version="0.6">
              <node id="1" lat="10.346000" lon="107.084000">
                <tag k="place" v="town"/><tag k="name" v="Vũng Tàu"/>
              </node>
              <node id="2" lat="10.823000" lon="106.630000">
                <tag k="place" v="city"/><tag k="name" v="Hồ Chí Minh"/>
              </node>
            </osm>
            """;

    /**
     * The whole point: the bytes were cached and the work was not, so a
     * hundred megabytes was re-parsed on every press and every restart.
     *
     * <p>Proved by breaking the XML after the first fetch. If the second one
     * still answers, it cannot have come from the XML.</p>
     */
    @Test
    void theSecondFetchReadsTheParsedFormRatherThanTheXml(@TempDir Path dir) throws Exception {
        final var hits = new java.util.concurrent.atomic.AtomicInteger();
        final var server = serving(TWO_PLACES, hits);
        try {
            final Cache cache = new Cache(dir);
            final OverpassClient client = new OverpassClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), cache);
            final BoundingBox bbox = BoundingBox.of(10, 106, 11, 108);

            final List<Feature> first = client.fetch(bbox, List.of(FeatureKind.PLACE));
            assertEquals(2, first.size());
            assertEquals(1, hits.get());

            final Path parsed = onlyFile(dir, ".features.bin");
            assertTrue(Files.size(parsed) > 0, "the parsed form was written beside the response");

            // Make the XML unparseable, keeping its timestamp: anything that
            // still works is not coming from it. The timestamp matters -
            // rewriting the file makes it newer than the parsed form, which
            // the guard below would then rightly reject, and the test would
            // pass for the wrong reason.
            final Path xml = onlyFile(dir, ".osm.xml");
            final var xmlTime = Files.getLastModifiedTime(xml);
            Files.writeString(xml, "<not osm at all");
            Files.setLastModifiedTime(xml, xmlTime);

            final List<Feature> second = client.fetch(bbox, List.of(FeatureKind.PLACE));
            assertEquals(2, second.size());
            assertEquals(1, hits.get(), "and no second request either");
            assertEquals(Set.of("Vũng Tàu", "Hồ Chí Minh"),
                         second.stream().map(Feature::name).collect(java.util.stream.Collectors.toSet()));
        }
        finally {
            server.stop(0);
        }
    }

    /**
     * A derivation must never outlive what it derives from. The refetch path
     * deletes it, but a cache directory is somewhere files get copied about
     * and restored from backups.
     */
    @Test
    void aParsedFormOlderThanTheResponseIsIgnored(@TempDir Path dir) throws Exception {
        final var hits = new java.util.concurrent.atomic.AtomicInteger();
        final var server = serving(TWO_PLACES, hits);
        try {
            final Cache cache = new Cache(dir);
            final OverpassClient client = new OverpassClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), cache);
            final BoundingBox bbox = BoundingBox.of(10, 106, 11, 108);
            client.fetch(bbox, List.of(FeatureKind.PLACE));

            // Backdate the parsed form behind the response, and put something
            // recognisably different in it.
            final Path parsed = onlyFile(dir, ".features.bin");
            FeatureStore.write(new FeatureStore.Extract(
                    new FeatureStore.Header(BoundingBox.of(10, 106, 11, 108),
                                            List.of(FeatureKind.PLACE), "city|town|village"),
                    List.of(new Feature(FeatureKind.PLACE, List.of(new double[]{0, 0}),
                                        java.util.Map.of("name", "Stale")))), parsed);
            Files.setLastModifiedTime(parsed, java.nio.file.attribute.FileTime.from(
                    Files.getLastModifiedTime(onlyFile(dir, ".osm.xml")).toInstant()
                            .minusSeconds(60)));

            final List<Feature> again = client.fetch(bbox, List.of(FeatureKind.PLACE));
            assertEquals(2, again.size(), "parsed from the XML again");
            assertTrue(again.stream().noneMatch(f -> "Stale".equals(f.name())), "and not from that");
            assertEquals(1, hits.get(), "without going back to the server");
        }
        finally {
            server.stop(0);
        }
    }

    private static Path onlyFile(Path dir, String suffix) throws Exception {
        try (var walk = Files.walk(dir)) {
            return walk.filter(f -> f.getFileName().toString().endsWith(suffix))
                    .findFirst().orElseThrow(() -> new AssertionError("no " + suffix + " under " + dir));
        }
    }

    // ---- finding it again after a restart ------------------------------------

    /**
     * The reported case: load detail, quit, come back, and the download is
     * still there. The ordinary cache key cannot do this - it is a hash of a
     * rectangle derived from the window size, and a window one pixel
     * different produces a different key and a complete miss.
     */
    @Test
    void findsAnEarlierDownloadThatCoversTheSameGround(@TempDir Path dir) throws Exception {
        final var hits = new java.util.concurrent.atomic.AtomicInteger();
        final var server = serving(TWO_PLACES, hits);
        try {
            final Cache cache = new Cache(dir);
            final OverpassClient client = new OverpassClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), cache);
            final List<FeatureKind> kinds = List.of(FeatureKind.PLACE);

            client.fetch(BoundingBox.of(10, 106, 11, 108), kinds);
            assertEquals(1, hits.get());

            // A new client, as if the application had been restarted, asking
            // about a view a hair different from last time.
            final OverpassClient after = new OverpassClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), cache);
            final OsmSource.Cached held =
                    after.cachedCovering(BoundingBox.of(10.01, 106.02, 10.99, 107.97), kinds);

            assertNotNull(held, "nothing found for ground already downloaded");
            assertEquals(2, held.features().size());
            assertEquals(1, hits.get(), "and nothing asked of the server");
            assertTrue(held.area().contains(BoundingBox.of(10.01, 106.02, 10.99, 107.97)),
                       "the area reported is the one actually held");
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotOfferAnExtractThatFallsShortOfTheArea(@TempDir Path dir) throws Exception {
        final var hits = new java.util.concurrent.atomic.AtomicInteger();
        final var server = serving(TWO_PLACES, hits);
        try {
            final Cache cache = new Cache(dir);
            final OverpassClient client = new OverpassClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), cache);
            client.fetch(BoundingBox.of(10, 106, 11, 108), List.of(FeatureKind.PLACE));

            assertNull(client.cachedCovering(BoundingBox.of(9, 105, 12, 109),
                                             List.of(FeatureKind.PLACE)),
                       "a wider view is not covered by a narrower download");
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotOfferAnExtractMissingAKindThatWasAskedFor(@TempDir Path dir) throws Exception {
        final var hits = new java.util.concurrent.atomic.AtomicInteger();
        final var server = serving(TWO_PLACES, hits);
        try {
            final Cache cache = new Cache(dir);
            final OverpassClient client = new OverpassClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), cache);
            final BoundingBox area = BoundingBox.of(10, 106, 11, 108);
            client.fetch(area, List.of(FeatureKind.PLACE));

            assertNull(client.cachedCovering(area,
                               List.of(FeatureKind.PLACE, FeatureKind.COASTLINE)),
                       "places alone cannot answer a request that wants a coastline");
        }
        finally {
            server.stop(0);
        }
    }

    /**
     * And not one asked at a coarser place query. Overpass is asked for fewer
     * place values as the area grows, so a wide extract genuinely holds fewer
     * names - handing it back close in would quietly lose every village.
     */
    @Test
    void doesNotOfferAnExtractAskedAtACoarserDetail(@TempDir Path dir) throws Exception {
        final var hits = new java.util.concurrent.atomic.AtomicInteger();
        final var server = serving(TWO_PLACES, hits);
        try {
            final Cache cache = new Cache(dir);
            final OverpassClient client = new OverpassClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), cache);

            // Fifteen degrees asks for cities only.
            final BoundingBox wide = BoundingBox.of(0, 100, 15, 115);
            assertEquals("city", OverpassClient.placeTypesFor(wide));
            client.fetch(wide, List.of(FeatureKind.PLACE));

            // Half a degree inside it wants villages and hamlets as well.
            final BoundingBox close = BoundingBox.of(5, 105, 5.5, 105.5);
            assertEquals("city|town|village|hamlet", OverpassClient.placeTypesFor(close));
            assertNull(client.cachedCovering(close, List.of(FeatureKind.PLACE)));
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void anEmptyCacheOffersNothingRatherThanFailing(@TempDir Path dir) {
        final OverpassClient client = new OverpassClient(
                URI.create("http://127.0.0.1:1/"), new Cache(dir));
        assertNull(client.cachedCovering(BoundingBox.of(10, 106, 11, 108),
                                         List.of(FeatureKind.PLACE)));
        assertNull(client.cachedCovering(BoundingBox.of(10, 106, 11, 108), List.of()));
    }

    /**
     * The margin decides what is downloaded; the view decides how fine it is.
     *
     * <p>Letting the margined box decide both asked Overpass for cities and
     * towns while the renderer went on drawing villages that had never been
     * fetched - and made the stored extract too coarse to answer for the very
     * view it had been fetched for, so nothing was ever reused.</p>
     */
    @Test
    void thePlaceQueryFollowsTheViewAndNotTheMarginAroundIt(@TempDir Path dir) throws Exception {
        final var hits = new java.util.concurrent.atomic.AtomicInteger();
        final var server = serving(TWO_PLACES, hits);
        try {
            final Cache cache = new Cache(dir);
            final OverpassClient client = new OverpassClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), cache);

            final BoundingBox view = BoundingBox.of(10, 106.5, 11.25, 107.75);   // 1.25 deg
            final BoundingBox downloaded = view.expanded(0.4375);                // + margin
            assertEquals("city|town|village", OverpassClient.placeTypesFor(view));
            assertEquals("city|town", OverpassClient.placeTypesFor(downloaded),
                         "which is what the margin would have asked for");

            client.fetch(downloaded, view, List.of(FeatureKind.PLACE));

            final FeatureStore.Header header = FeatureStore.readHeader(onlyFile(dir, ".features.bin"));
            assertEquals("city|town|village", header.placeTypes(), "the view's detail, not the margin's");
            assertEquals(downloaded, header.area(), "over the ground the margin covers");

            // And so it answers for that view, which is the whole point.
            assertNotNull(client.cachedCovering(view, List.of(FeatureKind.PLACE)));
        }
        finally {
            server.stop(0);
        }
    }

    /**
     * A restart with a slightly <em>larger</em> window. The margined box grows
     * with it, so asking the cache about that box missed - a coin flip on
     * which way the window had moved. The question has to be about the view.
     */
    @Test
    void aSlightlyLargerViewIsStillCoveredByTheMarginFetchedForTheOldOne(@TempDir Path dir)
            throws Exception {
        final var hits = new java.util.concurrent.atomic.AtomicInteger();
        final var server = serving(TWO_PLACES, hits);
        try {
            final Cache cache = new Cache(dir);
            final OverpassClient client = new OverpassClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), cache);
            final List<FeatureKind> kinds = List.of(FeatureKind.PLACE);

            final BoundingBox before = BoundingBox.of(10.13889, 106.375, 10.86111, 107.625);
            client.fetch(before.expanded(0.4375), before, kinds);
            assertEquals(1, hits.get());

            // One pixel taller on the next run, in the direction that used to miss.
            final BoundingBox after = BoundingBox.of(10.13860, 106.375, 10.86140, 107.625);
            assertNotNull(client.cachedCovering(after, kinds),
                          "a hair larger is still well inside the margin");
            assertEquals(1, hits.get());
        }
        finally {
            server.stop(0);
        }
    }
}
