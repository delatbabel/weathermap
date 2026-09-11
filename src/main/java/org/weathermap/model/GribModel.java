package org.weathermap.model;

/**
 * A NOAA forecast model available from NOMADS, and the facts needed to build a
 * request for it.
 *
 * <p>Each model has its own {@code filter_*.pl} CGI endpoint. Those endpoints
 * all take the same parameters - {@code file}, {@code dir}, {@code var_*},
 * {@code lev_*} and the {@code subregion} corners - which is what lets one
 * client talk to all of them (see
 * {@link org.weathermap.grib.NomadsClient}).</p>
 *
 * <p><b>Why a subsetting endpoint matters.</b> A full GFS 0.25-degree analysis
 * file is several hundred megabytes; the same field cut to one country and one
 * variable is a few tens of kilobytes. Since the application is built around
 * selecting a rectangle, asking the server to cut is not an optimisation, it is
 * the design.</p>
 */
public enum GribModel {

    /** Global, 0.25 deg, 16 days, runs every 6 hours. The default. */
    GFS_0P25("gfs_0p25", "GFS 0.25°", "filter_gfs_0p25.pl",
             "gfs.%1$s/%2$02d/atmos", "gfs.t%2$02dz.pgrb2.0p25.f%3$03d",
             0.25, new int[]{0, 6, 12, 18}, 384),

    /** Global, 0.5 deg - a quarter of the bytes when the detail is not needed. */
    GFS_0P50("gfs_0p50", "GFS 0.5°", "filter_gfs_0p50.pl",
             "gfs.%1$s/%2$02d/atmos", "gfs.t%2$02dz.pgrb2full.0p50.f%3$03d",
             0.5, new int[]{0, 6, 12, 18}, 384),

    /** North America, 12 km, 84 hours. */
    NAM_12KM("nam", "NAM 12 km", "filter_nam.pl",
             "nam.%1$s", "nam.t%2$02dz.awphys%3$02d.tm00.grib2",
             0.11, new int[]{0, 6, 12, 18}, 84),

    /** CONUS, 3 km, 48 hours, hourly runs - the highest resolution here. */
    HRRR_3KM("hrrr", "HRRR 3 km", "filter_hrrr_2d.pl",
             "hrrr.%1$s/conus", "hrrr.t%2$02dz.wrfsfcf%3$02d.grib2",
             0.03, allHours(), 48);

    private final String id;
    private final String displayName;
    private final String filterScript;
    private final String dirPattern;
    private final String filePattern;
    private final double resolutionDegrees;
    private final int[] cycleHours;
    private final int maxForecastHour;

    GribModel(String id, String displayName, String filterScript, String dirPattern,
              String filePattern, double resolutionDegrees, int[] cycleHours,
              int maxForecastHour) {
        this.id = id;
        this.displayName = displayName;
        this.filterScript = filterScript;
        this.dirPattern = dirPattern;
        this.filePattern = filePattern;
        this.resolutionDegrees = resolutionDegrees;
        this.cycleHours = cycleHours;
        this.maxForecastHour = maxForecastHour;
    }

    private static int[] allHours() {
        final int[] h = new int[24];
        for (int i = 0; i < 24; i++) h[i] = i;
        return h;
    }

    public String id() { return id; }

    public String displayName() { return displayName; }

    /** The {@code filter_*.pl} script name under {@code /cgi-bin/}. */
    public String filterScript() { return filterScript; }

    /** Native grid spacing, used to size the render and to warn about over-zooming. */
    public double resolutionDegrees() { return resolutionDegrees; }

    /** The run hours this model publishes, ascending. */
    public int[] cycleHours() { return cycleHours.clone(); }

    public int maxForecastHour() { return maxForecastHour; }

    /**
     * The {@code dir} parameter for one run.
     *
     * @param yyyymmdd the run date as {@code 20260911}
     * @param cycle    the run hour
     */
    public String directory(String yyyymmdd, int cycle) {
        return "/" + String.format(java.util.Locale.ROOT, dirPattern, yyyymmdd, cycle);
    }

    /** The {@code file} parameter for one forecast hour of one run. */
    public String fileName(String yyyymmdd, int cycle, int forecastHour) {
        return String.format(java.util.Locale.ROOT, filePattern, yyyymmdd, cycle, forecastHour);
    }

    /** @return the model with this {@link #id()}, or {@code null}. */
    public static GribModel byId(String id) {
        for (GribModel m : values()) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }

    @Override
    public String toString() { return displayName; }
}
