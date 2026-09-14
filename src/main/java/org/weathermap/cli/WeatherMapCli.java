package org.weathermap.cli;

import org.weathermap.MapService;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribCatalog;
import org.weathermap.model.GribLevel;
import org.weathermap.model.GribModel;
import org.weathermap.model.GribSelection;
import org.weathermap.model.Preferences;
import org.weathermap.model.ProfileStore;
import org.weathermap.model.Profile;
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
              --series STEP,AHEAD   a chart every STEP hours covering AHEAD hours
              --series STEP,BACK,AHEAD  from now, e.g. 3,48 or 3,24,48 to start
                                    24 hours in the past. Past charts come from
                                    the runs of the time, so their lead is short
                                    and often zero - the model's own analysis.
                                    Repeats correctly on a schedule, unlike a
                                    fixed list of hours
              --run YYYYMMDD:HH     pin a model run instead of using the latest
              --out DIR             where to write the PNGs
              --size WxH            maximum output size (default 1600x1200)
              --opacity 0..1        how strongly the field covers the base map
              --mercator            use Mercator instead of equirectangular
              --timezone ZONE       write chart times in this zone, e.g.
                                    Asia/Bangkok or UTC (default: this machine's)
              --rename "OLD=NEW"    print NEW on the chart wherever the map data
                                    says OLD, e.g. "South China Sea=East Sea".
                                    May be given more than once; --save keeps it
              --start WHEN          begin the series at WHEN instead of now, as
                                    a date(1) expression: 'tomorrow 06:00',
                                    '+12 hours'. A series posted in the evening
                                    is usually about tomorrow, and one starting
                                    at "now" begins with a chart of this evening
              --profile NAME        download the named saved profile - its area,
                                    model, hours or series, variables and levels.
                                    Other options still override it.
              --list-profiles       print the saved profile names and exit
              --config FILE         a preferences file other than the default
              --post                post the charts to Instagram as a carousel,
                                    using the account set up in the desktop
                                    application (Share -> Post to Instagram...)
              --caption TEXT        the caption, overriding the stored one.
                                    Parameters are expanded when it is posted:
                                    ${tomorrow:'+%A %e %B %Y'}
              --caption-file FILE   read the caption from a file, for one with
                                    line breaks in it
              --count N             how many charts to post, 2 to 10 (default 4)
              --save                store the overrides as the new defaults
              --dry-run             report what would be fetched, fetch nothing.
                                    --save still applies: settings are stored
              --quiet               print nothing to stdout - for cron. Everything
                                    goes to ~/.weathermap/weathermap.log instead,
                                    including the logging of every class that
                                    does any. Failures still reach stderr, so a
                                    broken job is still noticed
              --log FILE            write the log here instead, and keep writing
                                    it even when not quiet
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

        // Opened before anything is reported, and closed on every way out, so
        // an unattended run leaves a record of itself whatever happened.
        try (RunLog log = RunLog.open(options.quiet, options.logFile)) {
            return run(options, log);
        }
    }

    private static int run(Options options, RunLog log) {
        final ProfileStore profiles = new ProfileStore();
        if (options.listProfiles) {
            final List<String> names = profiles.names();
            if (names.isEmpty()) {
                log.say("no saved profiles in " + profiles.directory());
            }
            else {
                names.forEach(System.out::println);
            }
            return 0;
        }

        final Preferences prefs = (options.configFile == null)
                ? new Preferences() : new Preferences(options.configFile);

        // A profile stands in for the stored preferences, and the command line
        // still overrides it - which is what makes "the usual area, but tomorrow
        // as well" a one-flag change rather than a new profile.
        Profile profile = null;
        if (options.profile != null) {
            profile = profiles.load(options.profile).orElse(null);
            if (profile == null) {
                log.problem("weathermap: no profile called \"" + options.profile
                        + "\" in " + profiles.directory());
                final List<String> names = profiles.names();
                if (!names.isEmpty()) {
                    log.problem("  saved profiles: " + String.join(", ", names));
                }
                return 1;
            }
        }

        final BoundingBox area = (options.area != null) ? options.area
                : (profile != null ? profile.area() : prefs.area());
        final GribSelection selection = buildSelection(
                profile != null ? profile.selection() : prefs.selection(), options);
        final RenderSpec spec = buildRenderSpec(prefs, options);
        final Path outputDir = (options.outputDir != null) ? options.outputDir : prefs.outputDir();

        // --start moves the whole window rather than only its beginning, so
        // "--start 'tomorrow 06:00' --series 3,48" is 48 hours of charts from
        // tomorrow morning and not 48 hours from now with the front cut off.
        if (options.start != null) {
            if (!selection.hasSeries()) {
                log.problem("weathermap: --start needs a series; add --series STEP,AHEAD");
                return 2;
            }
            try {
                final java.time.ZonedDateTime from = java.time.ZonedDateTime.now(spec.zone());
                final java.time.ZonedDateTime begin =
                        org.weathermap.text.DateExpression.evaluate(options.start, from);
                final int ahead = (int) java.time.Duration.between(from, begin).toHours();
                selection.setSeries(selection.seriesStepHours(), -ahead,
                                    ahead + selection.seriesSpanHours());
                log.say("start     " + begin + " (" + options.start + ")");
            }
            catch (IllegalArgumentException e) {
                log.problem("weathermap: --start " + options.start + ": " + e.getMessage());
                return 2;
            }
        }

        // Everything Instagram needs is checked before anything is downloaded.
        // This runs from cron: finding out that the token is missing after
        // fetching and compositing eight charts wastes the work and, worse,
        // reports the failure far from its cause.
        org.weathermap.model.InstagramAccount account = null;
        String caption = null;
        if (options.post) {
            final Object[] checked = prepareToPost(options, spec, log);
            if (checked == null) return 2;
            account = (org.weathermap.model.InstagramAccount) checked[0];
            caption = (String) checked[1];
        }

        // Repair the selection rather than report it. A variable asked for at a
        // level it is not published at makes the whole request match nothing -
        // NOMADS applies the level filter across every variable - and the
        // service answers that with an empty body and HTTP 200. Unattended, in
        // a crontab, "matched no GRIB records" at 00:10 is not a thing anyone
        // is going to debug; quietly adding the level that was always meant is.
        final List<GribLevel> added = selection.addMissingLevels();

        final List<org.weathermap.model.ChartRequest> requests =
                selection.chartRequests(java.time.ZonedDateTime.now());

        if (profile != null) log.say("profile   " + profile.name());
        log.say("area      " + area);
        log.say("selection " + selection);
        if (selection.hasSeries()) {
            log.say("series    " + describeSeries(requests));
        }
        if (!added.isEmpty()) {
            final List<String> names = new ArrayList<>();
            for (GribLevel level : added) names.add(level.displayName());
            log.say("added     " + String.join(", ", names)
                    + " - the selected variables are not published at the "
                    + "levels that were stored");
        }
        log.say("output    " + outputDir);
        if (!spec.labelNames().isEmpty()) {
            final List<String> shown = new ArrayList<>();
            for (var rename : spec.labelNames().entrySet()) {
                shown.add(rename.getKey() + " -> " + rename.getValue());
            }
            log.say("labels    " + String.join(", ", shown));
        }
        if (options.post) {
            log.say("instagram " + account + ", " + options.postCount + " charts");
            // Already expanded, so this is the text that will be published.
            log.say("caption   " + caption.strip().replace("\n", " / "));
        }

        if (options.dryRun) {
            // --save is still honoured: storing a setting is not fetching
            // anything, and "--rename ... --save --dry-run" quietly doing
            // nothing is a trap - it looks exactly like the run that works.
            if (options.save) saveDefaults(prefs, area, selection, spec, log);
            log.say("dry run - nothing downloaded"
                    + (options.post ? ", nothing posted" : ""));
            return 0;
        }

        final MapService service = new MapService();
        log.say("decoder   " + service.reader().description());

        try {
            final List<MapService.Result> results = service.run(
                    area, selection, spec, outputDir,
                    log::say);

            if (results.isEmpty()) {
                log.problem("weathermap: nothing was rendered - "
                        + "the request matched no GRIB records");
                return 1;
            }
            for (MapService.Result r : results) {
                log.say(r.pngFile().toString());
            }
            if (options.post) {
                final int status = post(results, account, caption, options, log);
                if (status != 0) return status;
            }
            if (options.save) saveDefaults(prefs, area, selection, spec, log);
            return 0;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.problem("weathermap: interrupted");
            return 130;
        }
        catch (Exception e) {
            log.problem("weathermap: " + e.getMessage());
            return 1;
        }
    }

    private static void saveDefaults(Preferences prefs, BoundingBox area,
                                     GribSelection selection, RenderSpec spec, RunLog log) {
        prefs.setArea(area);
        prefs.setSelection(selection);
        prefs.setRenderSpec(spec);
        prefs.save();
        log.say("saved defaults to " + prefs.file());
    }

    /**
     * Reads and checks everything needed to post, before anything is fetched.
     *
     * <p>The account is the one the desktop application stored - there is no
     * separate command-line copy of it, and no way to pass a token as an
     * argument, because an argument is visible in {@code ps} to every user on
     * the machine and ends up in the shell history besides.</p>
     *
     * @return {account, caption}, or null when something is wrong, having said
     *         what on stderr
     */
    private static Object[] prepareToPost(Options options, RenderSpec spec, RunLog log) {
        final java.nio.file.Path file = org.weathermap.model.InstagramAccount.defaultFile();
        final org.weathermap.model.InstagramAccount account =
                org.weathermap.model.InstagramAccount.load(file).orElse(null);

        if (account == null) {
            log.problem("weathermap: no Instagram account in " + file);
            log.problem("  Set one up in the desktop application: "
                    + "Share -> Post to Instagram..., then Check settings.");
            return null;
        }
        if (!account.isComplete()) {
            log.problem("weathermap: the stored Instagram account is incomplete: "
                    + account);
            log.problem("  It needs an account ID, a token, a publish folder and "
                    + "that folder's public URL.");
            return null;
        }

        String caption = options.caption;
        if (options.captionFile != null) {
            try {
                caption = java.nio.file.Files.readString(options.captionFile);
            }
            catch (java.io.IOException e) {
                log.problem("weathermap: could not read " + options.captionFile
                        + ": " + e.getMessage());
                return null;
            }
        }
        if (caption == null) caption = account.caption();

        // Expanded now rather than after the download, so a caption that cannot
        // be worked out costs nothing and is reported before the work.
        final java.time.ZonedDateTime now = java.time.ZonedDateTime.now(spec.zone());
        final var broken = org.weathermap.text.CaptionTemplate.problems(
                caption, now, java.util.Locale.getDefault());
        if (!broken.isEmpty()) {
            log.problem("weathermap: the caption has a parameter that cannot "
                    + "be worked out:");
            for (var problem : broken) log.problem("  " + problem);
            return null;
        }

        if (options.postCount < 2
                || options.postCount > org.weathermap.instagram.InstagramClient.MAX_CAROUSEL) {
            log.problem("weathermap: --count must be 2 to "
                    + org.weathermap.instagram.InstagramClient.MAX_CAROUSEL
                    + ", not " + options.postCount);
            return null;
        }
        return new Object[]{account,
                org.weathermap.text.CaptionTemplate.expand(
                        caption, now, java.util.Locale.getDefault())};
    }

    /**
     * Writes the images, waits for them to be fetchable, and posts them.
     *
     * <p>The same three steps the window takes, in the same order and through
     * the same classes: there is one publishing path, so a post made from cron
     * cannot differ from one made by hand.</p>
     */
    private static int post(List<MapService.Result> results,
                            org.weathermap.model.InstagramAccount account,
                            String caption, Options options, RunLog log) {
        final java.util.function.Consumer<String> say = log.sink();
        try {
            final List<MapService.Result> charts =
                    org.weathermap.instagram.ChartPublisher.selectFrom(
                            results, 0, options.postCount);
            if (charts.size() < 2) {
                log.problem("weathermap: a carousel needs at least two charts, and "
                        + "only " + charts.size() + " was rendered - ask for a series");
                return 1;
            }

            say.accept("writing " + charts.size() + " images for Instagram to fetch");
            final var images = new org.weathermap.instagram.ChartPublisher(account).publish(charts);

            org.weathermap.instagram.PublishGate.sync(account, say);
            org.weathermap.instagram.PublishGate.awaitReachable(images, say);

            final var client = new org.weathermap.instagram.InstagramClient(account);
            final String mediaId = client.postCarousel(images, caption, say);
            log.say("posted    " + mediaId);
            client.permalink(mediaId).ifPresent(link -> log.say("post      " + link));
            return 0;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.problem("weathermap: interrupted while posting");
            return 130;
        }
        catch (Exception e) {
            log.problem("weathermap: the post failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * What a series actually resolved to.
     *
     * <p>A list of forecast hours no longer describes it: a series reaching into
     * the past draws on several runs, and the same hour can appear more than
     * once meaning different moments. The valid times and the worst lead are
     * what a reader needs - the lead being how much of the series is analysis
     * and how much is forecast.</p>
     */
    private static String describeSeries(List<org.weathermap.model.ChartRequest> requests) {
        if (requests.isEmpty()) return "no run covers the requested times";

        final org.weathermap.model.ChartRequest first = requests.get(0);
        final org.weathermap.model.ChartRequest last = requests.get(requests.size() - 1);
        int analyses = 0;
        int worstLead = 0;
        for (org.weathermap.model.ChartRequest r : requests) {
            if (r.isAnalysis()) analyses++;
            worstLead = Math.max(worstLead, r.leadHours());
        }
        return requests.size() + " charts, " + first.validTime() + " to " + last.validTime()
                + " (" + analyses + " analyses, longest lead f" + worstLead + ")";
    }

    /**
     * Splits {@code OLD=NEW}.
     *
     * <p>On the first {@code =}, so a replacement may contain one. A name with
     * an {@code =} in it is not a thing the gazetteer has.</p>
     */
    private static String[] parseRename(String argument) {
        final int at = argument.indexOf('=');
        if (at <= 0 || at == argument.length() - 1) {
            throw new IllegalArgumentException(
                    "--rename wants OLD=NEW, for example \"South China Sea=East Sea\", not \""
                    + argument + "\"");
        }
        return new String[]{argument.substring(0, at), argument.substring(at + 1)};
    }

    private static GribSelection buildSelection(GribSelection stored, Options options) {
        final GribSelection selection = stored;

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
        if (!options.hours.isEmpty()) {
            // An explicit list is the more specific instruction, so it wins and
            // turns the stored series off rather than being silently ignored.
            selection.clearSeries();
            selection.setForecastHours(options.hours);
        }
        if (options.seriesStep > 0) {
            selection.setSeries(options.seriesStep, options.seriesBack, options.seriesSpan);
        }

        if (options.runDate != null) selection.setRun(options.runDate, options.runCycle);
        else selection.useLatestRun();

        return selection;
    }

    private static RenderSpec buildRenderSpec(Preferences prefs, Options options) {
        final RenderSpec spec = prefs.renderSpec();
        if (options.width > 0 && options.height > 0) spec.setMaxSize(options.width, options.height);
        if (options.opacity >= 0) spec.setGribOpacity(options.opacity);
        if (options.mercator) spec.setMercator(true);
        if (options.zone != null) spec.setZone(options.zone);
        // Added to whatever is stored rather than replacing it, so one name can
        // be changed for a single run without losing the standing ones.
        for (String[] rename : options.renames) spec.renameLabel(rename[0], rename[1]);
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
        String profile;
        boolean listProfiles;
        java.time.ZoneId zone;
        int seriesStep;
        int seriesBack;
        int seriesSpan = org.weathermap.model.GribSelection.DEFAULT_SERIES_SPAN_HOURS;
        boolean save;
        boolean post;
        Path logFile;
        final List<String[]> renames = new ArrayList<>();
        String caption;
        Path captionFile;
        int postCount = 4;
        String start;
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
                    case "--series" -> {
                        final String[] parts = next(args, ++i, a).split(",");
                        if (parts.length < 2 || parts.length > 3) {
                            throw new IllegalArgumentException(
                                    "--series takes STEP,AHEAD or STEP,BACK,AHEAD "
                                    + "in hours, e.g. 3,48 or 3,24,48");
                        }
                        try {
                            o.seriesStep = Integer.parseInt(parts[0].trim());
                            o.seriesBack = parts.length == 3
                                    ? Integer.parseInt(parts[1].trim()) : 0;
                            o.seriesSpan = Integer.parseInt(parts[parts.length - 1].trim());
                        }
                        catch (NumberFormatException e) {
                            throw new IllegalArgumentException(
                                    "--series takes whole hours, e.g. 3,24,48");
                        }
                    }
                    case "--profile" -> o.profile = next(args, ++i, a);
                    case "--list-profiles" -> o.listProfiles = true;
                    case "--mercator" -> o.mercator = true;
                    case "--timezone", "--tz" -> {
                        final String id = next(args, ++i, a);
                        try {
                            o.zone = java.time.ZoneId.of(id);
                        }
                        catch (java.time.DateTimeException e) {
                            throw new IllegalArgumentException(
                                    "unknown time zone: " + id + " - use an IANA id "
                                    + "such as Asia/Bangkok, Europe/London or UTC");
                        }
                    }
                    case "--save" -> o.save = true;
                    case "--post" -> o.post = true;
                    case "--caption" -> o.caption = next(args, ++i, a);
                    case "--caption-file" -> o.captionFile = Path.of(next(args, ++i, a));
                    case "--count" -> o.postCount = Integer.parseInt(next(args, ++i, a));
                    case "--start" -> o.start = next(args, ++i, a);
                    case "--dry-run" -> o.dryRun = true;
                    case "--quiet", "-q" -> o.quiet = true;
                    case "--log" -> o.logFile = Path.of(next(args, ++i, a));
                    case "--rename" -> o.renames.add(parseRename(next(args, ++i, a)));
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
