package org.weathermap.grib.netcdf;

import org.weathermap.grib.GribReader;
import org.weathermap.grib.Grid;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A {@link GribReader} backed by NetCDF-Java, which decodes every GRIB2 packing
 * template and grid definition NOAA publishes - the JPEG2000 (5.40) and
 * complex-packed (5.2/5.3) fields found in raw published files, the
 * Lambert-conformal grids NAM and HRRR use, and the full WMO parameter table.
 *
 * <p>None of that is needed for a request made through a NOMADS
 * {@code filter_*.pl} endpoint, which re-encodes its output as a lat/lon grid
 * with simple packing that {@link org.weathermap.grib.Grib2Scanner} handles.
 * This reader is for every other source.</p>
 *
 * <p><b>This is the only class that links against a third-party library.</b> It
 * is compiled only when the {@code netcdf} Maven profile is active
 * ({@code ./mvnw package -Pnetcdf}) and loaded reflectively by
 * {@link org.weathermap.grib.GribReaders}, so the application builds, ships and
 * runs without it.</p>
 *
 * <p>That shape is deliberate: NetCDF-Java is a large dependency published from
 * Unidata's own repository rather than Maven Central, and most of it is not
 * needed here. Keeping the boundary at one class means the choice can be
 * revisited - or a second decoder added - without touching the renderer.</p>
 *
 * <h2>Implementation notes</h2>
 *
 * <p>The intended route is {@code ucar.nc2.dt.grid.GridDataset.open(path)}, then
 * for each {@code GridDatatype}:</p>
 *
 * <ol>
 *   <li>read the coordinate axes to build the {@link org.weathermap.model.BoundingBox}
 *       and grid dimensions;</li>
 *   <li>read the value array for the requested time and level as floats;</li>
 *   <li>flip the rows if the latitude axis is ascending, since {@link Grid}
 *       stores north-first;</li>
 *   <li>map the GRIB parameter name back onto a
 *       {@link org.weathermap.model.GribVariable} through
 *       {@link org.weathermap.model.GribCatalog#variable}.</li>
 * </ol>
 *
 * <p>TODO: implement the above. The class is present as the seam, and reports
 * itself unavailable until it is real.</p>
 */
public final class NetcdfGribReader implements GribReader {

    @Override
    public boolean isAvailable() {
        // TODO: return true once read() is implemented. Probe for the library
        // first - the class may be compiled in but the jar absent at run time:
        //   try { Class.forName("ucar.nc2.dt.grid.GridDataset"); return true; }
        //   catch (ClassNotFoundException e) { return false; }
        return false;
    }

    @Override
    public String description() {
        return "NetCDF-Java GRIB2 decoder";
    }

    @Override
    public List<Grid> read(Path gribFile) throws IOException {
        throw new IOException("NetcdfGribReader is not implemented yet; "
                + "see the class javadoc for the intended route");
    }
}
