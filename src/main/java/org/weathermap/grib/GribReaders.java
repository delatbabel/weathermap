package org.weathermap.grib;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Picks the best available {@link GribReader}.
 *
 * <p>The NetCDF-Java adapter is loaded reflectively so the application links and
 * runs without it: the class is not compiled at all unless the {@code netcdf}
 * Maven profile is active, and even when compiled its dependencies may be
 * missing at run time. Both cases end up here as "not available", and the caller
 * gets the built-in scanner with its limits.</p>
 *
 * <p>This mirrors how the VASSAL Extension Utility keeps its engine-linked
 * runner optional: the capability degrades to a clear message instead of a
 * {@code NoClassDefFoundError}.</p>
 */
public final class GribReaders {

    private static final Logger LOG = Logger.getLogger(GribReaders.class.getName());

    private static final String NETCDF_READER =
            "org.weathermap.grib.netcdf.NetcdfGribReader";

    private GribReaders() { }

    /**
     * @return the NetCDF-Java reader when it is present, otherwise the built-in
     *         scanner
     */
    public static GribReader best() {
        final GribReader netcdf = tryLoad(NETCDF_READER);
        if (netcdf != null && netcdf.isAvailable()) return netcdf;
        LOG.fine("Using the built-in GRIB2 reader. It handles lat/lon grids with "
                + "simple packing, which is what the NOMADS filter endpoints return. "
                + "Build with -Pnetcdf to also decode raw published files, "
                + "Lambert-conformal grids and complex or JPEG2000 packing.");
        return new Grib2Scanner();
    }

    private static GribReader tryLoad(String className) {
        try {
            return (GribReader) Class.forName(className).getDeclaredConstructor().newInstance();
        }
        catch (ClassNotFoundException e) {
            return null;                       // not compiled in: the normal case
        }
        catch (ReflectiveOperationException | LinkageError e) {
            LOG.log(Level.FINE, "Could not instantiate " + className, e);
            return null;
        }
    }
}
