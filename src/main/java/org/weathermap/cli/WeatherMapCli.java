package org.weathermap.cli;

import org.weathermap.MapService;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribCatalog;
import org.weathermap.model.GribLevel;
import org.weathermap.model.GribModel;
import org.weathermap.model.GribSelection;
import org.weathermap.model.Preferences;
import org.weathermap.model.RenderSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The headless half: takes the last area and GRIB selection the desktop
 * application stored, downloads the newest data for it, composites, and writes
 * the PNGs.
 *
 * <p>With no arguments it does exactly that - which is the point. It is built to
 * be the thing in a crontab:</p>
 *
 * <pre>
 *   10 0,6,12,18 * * *  java -jar weathermap.jar --cli
 * </pre>
 *
 * <p>Every stored value can still be overridden on the command line, so one
 * saved selection can be reused for a different area or a different variable
 * without opening the UI.</p>
 *
 * <p><b>The run is always the latest published one</b> unless {@code --run} says
 * otherwise, even when the stored selection pinned a run. A pinned run is a
 * choice made in the UI about a particular forecast; repeating it every six
 * hours from cron would redraw the same map forever.</p>
 */
public final class WeatherMapCli {

    private static final String USAGE = """
            usage: weathermap --cli [options]

            With no options, repeats the last area and GRIB selection made in the
            desktop application, using the latest published model run.

              --area W,S,E,N        override the stored area
              --model ID            gfs_0p25 | gfs_0p50 | nam | hrrr
              --var CODE[,CODE...]  NOMADS variable codes, e.g. TMP,APCP
              --level CODE[,...]    NOMADS level codes, e.g. 2_m_above_ground
              --hours H[,H-H...]    forecast hours, e.g. 0,6,12-24
              --run YYYYMMDD:HH     pin a model run instead of using the latest
              --out DIR             where to write the PNGs
              --size WxH            maximum output size (default 1600x1200)
              --opacity 0..1        how strongly the field covers the base map
              --mercator            use Mercator instead of equirectangular
              --config FILE         a preferences file other than the default
              --save                store the overrides as the new defaults
              --dry-run             report what would be fetched, fetch nothing
              --quiet               only report errors
              --help                this message
            """;

    private WeatherMapCli() { }

    /** @return the process exit status */
    public static int run(String[] args) {
        final Options options;
        try {
            options = Options.parse(args);
        }
        catch (IllegalArgumentException e) {
            System.err.println("weathermap: " + e.getMessage());
            System.err.println();
            System.err.print(USAGE);
            return 2;
        }
        if (options.help) {
            System.out.print(USAGE);
            return 0;
        }

        final Preferences prefs = (options.configFile == null)
                ? new Preferences() : new Preferences(options.configFile);

        final BoundingBox area = (options.area != null) ? options.area : prefs.area();
        final GribSelection selection = buildSelection(prefs, options);
        final RenderSpec spec = buildRenderSpec(prefs, options);
        final Path outputDir = (options.outputDir != null) ? options.outputDir : prefs.outputDir();

        // Repair the selection rather than report it. A variable asked for at a
        // level it is not published at makes the whole request match nothing -
        // NOMADS applies the level filter across every variable - and the
        // service answers that with an empty body and HTTP 200. Unattended, in
        // a crontab, "matched no GRIB records" at 00:10 is not a thing anyone
        // is going to debug; quietly adding the level that was always meant is.
        final List<GribLevel> added = selection.addMissingLevels();

        if (!options.quiet) {
            System.out.println("area      " + area);
            System.out.println("selection " + selection);
            if (!added.isEmpty()) {
                final List<String> names = new ArrayList<>();
                for (GribLevel level : added) names.add(level.displayName());
                System.out.println("added     " + String.join(", ", names)
                        + " - the selected variables are not published at the "
                        + "levels that were stored");
            }
            System.out.println("output    " + outputDir);
        }

        if (options.dryRun) {
            System.out.println("dry run - nothing downloaded");
            return 0;
        }

        final MapService service = new MapService();
        if (!options.quiet) {
            System.out.println("decoder   " + service.reader().description());
        }

        try {
            final List<MapService.Result> results = service.run(
                    area, selection, spec, outputDir,
                    options.quiet ? MapService.Progress.SILENT : System.out::println);

            if (results.isEmpty()) {
                System.err.println("weathermap: nothing was rendered - "
                        + "the request matched no GRIB records");
                return 1;
            }
            for (MapService.Result r : results) {
                System.out.println(r.pngFile());
            }
            if (options.save) {
                prefs.setArea(area);
                prefs.setSelection(selection);
                prefs.setRenderSpec(spec);
                prefs.save();
                if (!options.quiet) System.out.println("saved defaults to " + prefs.file());
            }
            return 0;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("weathermap: interrupted");
            return 130;
        }
        catch (Exception e) {
            System.err.println("weathermap: " + e.getMessage());
            return 1;
        }
    }

    private static GribSelection buildSelection(Preferences prefs, Options options) {
        final GribSelection selection = prefs.selection();

        if (options.model != null) selection.setModel(options.model);
        if (!options.variables.isEmpty()) {
            final Set<org.weathermap.model.GribVariable> vars = new LinkedHashSet<>();
            for (String code : options.variables) vars.add(GribCatalog.variable(code));
            selection.setVariables(vars);
        }
        if (!options.levels.isEmpty()) {
            final Set<GribLevel> levels = new LinkedHashSet<>();
            for (String code : options.levels) levels.add(GribCatalog.level(code));
            selection.setLevels(levels);
        }
        if (!options.hours.isEmpty()) selection.setForecastHours(options.hours);

        if (options.runDate != null) selection.setRun(options.runDate, options.runCycle);
        else selection.useLatestRun();

        return selection;
    }

    private static RenderSpec buildRenderSpec(Preferences prefs, Options options) {
        final RenderSpec spec = prefs.renderSpec();
        if (options.width > 0 && options.height > 0) spec.setMaxSize(options.width, options.height);
        if (options.opacity >= 0) spec.setGribOpacity(options.opacity);
        if (options.mercator) spec.setMercator(true);
        return spec;
    }

    /** Parsed command line. Package-private so it can be asserted in tests. */
    static final class Options {
        BoundingBox area;
        GribModel model;
        final List<String> variables = new ArrayList<>();
        final List<String> levels = new ArrayList<>();
        final List<Integer> hours = new ArrayList<>();
        java.time.LocalDate runDate;
        int runCycle = -1;
        Path outputDir;
        Path configFile;
        int width = -1;
        int height = -1;
        float opacity = -1;
        boolean mercator;
        boolean save;
        boolean dryRun;
        boolean quiet;
        boolean help;

        static Options parse(String[] args) {
            final Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                final String a = args[i];
                switch (a) {
                    case "--cli" -> { }                       // consumed by Main
                    case "--help", "-h" -> o.help = true;
                    case "--mercator" -> o.mercator = true;
                    case "--save" -> o.save = true;
                    case "--dry-run" -> o.dryRun = true;
                    case "--quiet", "-q" -> o.quiet = true;
                    case "--area" -> o.area = parseArea(next(args, ++i, a));
                    case "--model" -> {
                        final String id = next(args, ++i, a);
                        o.model = GribModel.byId(id);
                        if (o.model == null) throw new IllegalArgumentException("unknown model: " + id);
                    }
                    case "--var" -> addCsv(o.variables, next(args, ++i, a));
                    case "--level" -> addCsv(o.levels, next(args, ++i, a));
                    case "--hours" -> o.hours.addAll(parseHours(next(args, ++i, a)));
                    case "--run" -> parseRun(o, next(args, ++i, a));
                    case "--out" -> o.outputDir = Path.of(next(args, ++i, a));
                    case "--config" -> o.configFile = Path.of(next(args, ++i, a));
                    case "--size" -> parseSize(o, next(args, ++i, a));
                    case "--opacity" -> o.opacity = Float.parseFloat(next(args, ++i, a));
                    default -> throw new IllegalArgumentException("unknown option: " + a);
                }
            }
            return o;
        }

        private static String next(String[] args, int i, String option) {
            if (i >= args.length) throw new IllegalArgumentException(option + " needs a value");
            return args[i];
        }

        private static BoundingBox parseArea(String s) {
            try {
                return BoundingBox.parse(s);
            }
            catch (RuntimeException e) {
                throw new IllegalArgumentException("--area: " + e.getMessage());
            }
        }

        private static void addCsv(List<String> out, String value) {
            for (String part : value.split(",")) {
                final String t = part.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }

        /** Accepts {@code 0,6,12-24}; shared shape with the UI's forecast-hour field. */
        private static List<Integer> parseHours(String value) {
            final Set<Integer> out = new java.util.TreeSet<>();
            for (String part : value.split(",")) {
                final String t = part.trim();
                if (t.isEmpty()) continue;
                final int dash = t.indexOf('-');
                try {
                    if (dash > 0) {
                        final int from = Integer.parseInt(t.substring(0, dash).trim());
                        final int to = Integer.parseInt(t.substring(dash + 1).trim());
                        if (to < from) {
                            throw new IllegalArgumentException("--hours: " + t + " runs backwards");
                        }
                        for (int h = from; h <= to; h++) out.add(h);
                    }
                    else {
                        out.add(Integer.parseInt(t));
                    }
                }
                catch (NumberFormatException e) {
                    throw new IllegalArgumentException("--hours: cannot read '" + t + "'");
                }
            }
            return new ArrayList<>(out);
        }

        private static void parseRun(Options o, String value) {
            final int colon = value.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException("--run wants YYYYMMDD:HH");
            try {
                final String d = value.substring(0, colon);
                o.runDate = java.time.LocalDate.of(
                        Integer.parseInt(d.substring(0, 4)),
                        Integer.parseInt(d.substring(4, 6)),
                        Integer.parseInt(d.substring(6, 8)));
                o.runCycle = Integer.parseInt(value.substring(colon + 1));
            }
            catch (RuntimeException e) {
                throw new IllegalArgumentException("--run wants YYYYMMDD:HH, got " + value);
            }
        }

        private static void parseSize(Options o, String value) {
            final int x = value.toLowerCase(java.util.Locale.ROOT).indexOf('x');
            if (x < 0) throw new IllegalArgumentException("--size wants WxH");
            try {
                o.width = Integer.parseInt(value.substring(0, x).trim());
                o.height = Integer.parseInt(value.substring(x + 1).trim());
            }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("--size wants WxH, got " + value);
            }
        }
    }
}
