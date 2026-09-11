package org.weathermap.grib;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Decodes a GRIB2 file into {@link Grid}s.
 *
 * <p>Two implementations, because how hard this is depends entirely on where the
 * file came from. The container is simple - eight numbered sections per message
 * - but the data section is packed by one of a dozen templates, and the raw
 * published GFS files use JPEG2000 (template 5.40), which cannot be decoded
 * without a JPEG2000 decoder.</p>
 *
 * <p>The NOMADS filter endpoints, however, <b>re-encode every subset they
 * serve</b>: what comes back is a regular lat/lon grid with simple packing,
 * verified against live GFS responses. So {@link Grib2Scanner} decodes the
 * normal path with no dependency at all, and the NetCDF-Java adapter in
 * {@code grib/netcdf} is there for raw files, Lambert-conformal grids and the
 * full WMO parameter table. See README.md.</p>
 */
public interface GribReader {

    /** Whether this reader's dependencies are present at run time. */
    boolean isAvailable();

    /** Human-readable, for telling the user which decoder produced a field. */
    String description();

    /**
     * Decodes every field in the file.
     *
     * @return one {@link Grid} per message, in file order
     * @throws IOException if the file is not GRIB2, or uses a packing this reader
     *                     cannot unpack
     */
    List<Grid> read(Path gribFile) throws IOException;
}
