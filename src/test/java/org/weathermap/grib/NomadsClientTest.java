package org.weathermap.grib;

import org.junit.jupiter.api.Test;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribCatalog;
import org.weathermap.model.GribSelection;
import org.weathermap.util.Cache;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
