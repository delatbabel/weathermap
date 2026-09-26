package org.weathermap.grib;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribCatalog;
import org.weathermap.model.GribSelection;
import org.weathermap.util.Cache;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NomadsClientTest {

    /**
     * Pins the exact request shape, because it was established by probing the
     * live service and every part of it is load-bearing.
     *
     * <p>In particular {@code subregion=} is a valueless flag: without it NOMADS
     * ignores the four corner parameters and returns the global field, which
     * fails as a 500 MB download rather than as an error.</p>
     */
    @Test
    void buildsTheFilterRequestNomadsAccepts() {
        final NomadsClient client =
                new NomadsClient(NomadsClient.DEFAULT_BASE, new Cache(Path.of("/tmp/unused")));
        final GribSelection selection = new GribSelection();
        selection.setVariables(java.util.Set.of(GribCatalog.TEMPERATURE));
        selection.setLevels(java.util.Set.of(GribCatalog.LEVEL_2M));

        final URI uri = client.buildUri(
                BoundingBox.of(49.5, -11, 61, 2), selection, "20260911", 0, 0);

        assertEquals("https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl"
                        + "?file=gfs.t00z.pgrb2.0p25.f000"
                        + "&var_TMP=on"
                        + "&lev_2_m_above_ground=on"
                        + "&subregion="
                        + "&leftlon=-11&rightlon=2&toplat=61&bottomlat=49.5"
                        + "&dir=%2Fgfs.20260911%2F00%2Fatmos",
                uri.toString());
    }

    // ---- across the antimeridian -----------------------------------------

    /**
     * {@code leftlon} has to be west of {@code rightlon}, so a Pacific box is
     * asked for as two subregions that meet at 180. These are the two requests
     * that go out, and the corners are the whole point of them.
     */
    @Test
    void asksForEachSideOfTheSeamSeparately() {
        final NomadsClient client =
                new NomadsClient(NomadsClient.DEFAULT_BASE, new Cache(Path.of("/tmp/unused")));
        final GribSelection selection = new GribSelection();
        selection.setVariables(java.util.Set.of(GribCatalog.TEMPERATURE));
        selection.setLevels(java.util.Set.of(GribCatalog.LEVEL_2M));

        final java.util.List<BoundingBox> halves =
                BoundingBox.of(-10, 170, 10, -170).halves();

        assertEquals(2, halves.size());
        assertTrue(client.buildUri(halves.get(0), selection, "20260911", 0, 0).toString()
                        .contains("&leftlon=170&rightlon=180&"),
                "the strip up to the antimeridian");
        assertTrue(client.buildUri(halves.get(1), selection, "20260911", 0, 0).toString()
                        .contains("&leftlon=-180&rightlon=-170&"),
                "and the strip on from it");
    }

    /**
     * And that the download loop actually issues both, pairing them into one
     * chart. A single request here would silently give half an ocean.
     */
    @Test
    void downloadsBothHalvesAsOneChart() throws Exception {
        final java.util.List<String> asked =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            asked.add(exchange.getRequestURI().getQuery());
            final byte[] body = "GRIB-ish".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();

        final Path cacheRoot = java.nio.file.Files.createTempDirectory("nomads-halves");
        try {
            final NomadsClient client = new NomadsClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                    new Cache(cacheRoot));
            final GribSelection selection = new GribSelection();
            selection.setVariables(java.util.Set.of(GribCatalog.TEMPERATURE));
            selection.setLevels(java.util.Set.of(GribCatalog.LEVEL_2M));

            final var request = new org.weathermap.model.ChartRequest(
                    java.time.LocalDate.of(2026, 9, 11), 0, 0);
            final var fetched = client.download(BoundingBox.of(-10, 170, 10, -170),
                    selection, java.util.List.of(request), null);

            assertEquals(1, fetched.size(), "one chart");
            assertEquals(2, fetched.get(0).files().size(), "in two parts");
            assertEquals(2, asked.size());
            assertTrue(asked.get(0).contains("leftlon=170&rightlon=180"), asked.get(0));
            assertTrue(asked.get(1).contains("leftlon=-180&rightlon=-170"), asked.get(1));
        }
        finally {
            server.stop(0);
        }
    }

    /** The ordinary case is still one request and one file. */
    @Test
    void anOrdinaryBoxIsStillOneRequest() throws Exception {
        final java.util.List<String> asked =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            asked.add(exchange.getRequestURI().getQuery());
            final byte[] body = "GRIB-ish".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();

        final Path cacheRoot = java.nio.file.Files.createTempDirectory("nomads-whole");
        try {
            final NomadsClient client = new NomadsClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                    new Cache(cacheRoot));
            final GribSelection selection = new GribSelection();
            selection.setVariables(java.util.Set.of(GribCatalog.TEMPERATURE));
            selection.setLevels(java.util.Set.of(GribCatalog.LEVEL_2M));

            final var fetched = client.download(BoundingBox.of(49.5, -11, 61, 2), selection,
                    java.util.List.of(new org.weathermap.model.ChartRequest(
                            java.time.LocalDate.of(2026, 9, 11), 0, 0)), null);

            assertEquals(1, asked.size());
            assertEquals(1, fetched.get(0).files().size());
        }
        finally {
            server.stop(0);
        }
    }
}
