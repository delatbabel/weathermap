package org.weathermap.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The last area and data selection, persisted so the next download starts where
 * the last one left off - and so the CLI has something to repeat with no
 * arguments at all.
 *
 * <p>Stored as a Java properties file at
 * {@code ~/.weathermap/preferences.properties}. All disk I/O <b>fails soft</b>:
 * a missing or unreadable file yields defaults and a failed write is logged and
 * swallowed, so preference bookkeeping can never stop a map being drawn.</p>
 *
 * <p>The format is properties rather than JSON for the same reason the rest of
 * the project has no dependencies: {@link Properties} is in the JDK, the file
 * stays hand-editable, and an unreadable value can be dropped individually
 * instead of failing the whole document.</p>
 */
public final class Preferences {

    private static final Logger LOG = Logger.getLogger(Preferences.class.getName());

    public static final String CONFIG_DIR = ".weathermap";
    public static final String CONFIG_FILE = "preferences.properties";

    private static final String KEY_BBOX = "area.bbox";
    private static final String KEY_MODEL = "grib.model";
    private static final String KEY_VARIABLES = "grib.variables";
    private static final String KEY_LEVELS = "grib.levels";
    private static final String KEY_FORECAST_HOURS = "grib.forecastHours";
    private static final String KEY_RUN_DATE = "grib.runDate";
    private static final String KEY_RUN_CYCLE = "grib.runCycle";
    private static final String KEY_OUTPUT_DIR = "output.dir";
    private static final String KEY_MAX_WIDTH = "render.maxWidth";
    private static final String KEY_MAX_HEIGHT = "render.maxHeight";
    private static final String KEY_MERCATOR = "render.mercator";
    private static final String KEY_GRIB_OPACITY = "render.gribOpacity";
    private static final String KEY_LAYERS = "render.layers";

    /** Shown the first time the application runs, before anything is selected. */
    public static final BoundingBox DEFAULT_AREA = BoundingBox.of(49.5, -11.0, 61.0, 2.0);

    private final Path file;
    private final Properties props = new Properties();

    public Preferences() {
        this(defaultFile());
    }

    /** Visible for tests, and for a {@code --config} override on the CLI. */
    public Preferences(Path file) {
        this.file = file;
        load();
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE);
    }

    public Path file() { return file; }

    private void load() {
        if (!Files.isReadable(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read " + file + "; using defaults", e);
        }
    }

    /** Writes the file, creating {@code ~/.weathermap} if needed. Never throws. */
    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "weathermap - last selected area and GRIB data set");
            }
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write " + file, e);
        }
    }

    // ---- the area -------------------------------------------------------

    /** @return the last selected area, or {@link #DEFAULT_AREA} if there is none. */
    public BoundingBox area() {
        final String s = props.getProperty(KEY_BBOX);
        if (s == null || s.isBlank()) return DEFAULT_AREA;
        try {
            return BoundingBox.parse(s);
        }
        catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Ignoring unreadable " + KEY_BBOX + "=" + s, e);
            return DEFAULT_AREA;
        }
    }

    public void setArea(BoundingBox bbox) {
        props.setProperty(KEY_BBOX, bbox.toString());
    }

    /** True when an area has actually been chosen, rather than defaulted. */
    public boolean hasArea() {
        final String s = props.getProperty(KEY_BBOX);
        return s != null && !s.isBlank();
    }

    // ---- the data selection ---------------------------------------------

    /** @return the last GRIB selection, or a default one. Unreadable parts are dropped. */
    public GribSelection selection() {
        final GribSelection sel = new GribSelection();

        final GribModel model = GribModel.byId(props.getProperty(KEY_MODEL, ""));
        if (model != null) sel.setModel(model);

        final Set<GribVariable> vars = new LinkedHashSet<>();
        for (String code : csv(KEY_VARIABLES)) vars.add(GribCatalog.variable(code));
        if (!vars.isEmpty()) sel.setVariables(vars);

        final Set<GribLevel> levels = new LinkedHashSet<>();
        for (String code : csv(KEY_LEVELS)) levels.add(GribCatalog.level(code));
        if (!levels.isEmpty()) sel.setLevels(levels);

        final List<Integer> hours = new ArrayList<>();
        for (String h : csv(KEY_FORECAST_HOURS)) {
            try {
                hours.add(Integer.parseInt(h));
            }
            catch (NumberFormatException e) {
                LOG.warning("Ignoring forecast hour " + h);
            }
        }
        if (!hours.isEmpty()) {
            try {
                sel.setForecastHours(hours);
            }
            catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Ignoring stored forecast hours " + hours, e);
            }
        }

        // A pinned run is only restored when both halves are readable. Anything
        // else falls back to "latest", which is what the CLI wants anyway.
        final String date = props.getProperty(KEY_RUN_DATE, "");
        final String cycle = props.getProperty(KEY_RUN_CYCLE, "");
        if (!date.isBlank() && !cycle.isBlank()) {
            try {
                sel.setRun(LocalDate.parse(date), Integer.parseInt(cycle));
            }
            catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Ignoring stored run " + date + " " + cycle, e);
            }
        }
        return sel;
    }

    public void setSelection(GribSelection sel) {
        props.setProperty(KEY_MODEL, sel.model().id());
        props.setProperty(KEY_VARIABLES, join(sel.variables().stream().map(GribVariable::code).toList()));
        props.setProperty(KEY_LEVELS, join(sel.levels().stream().map(GribLevel::code).toList()));
        props.setProperty(KEY_FORECAST_HOURS, join(sel.forecastHours().stream().map(String::valueOf).toList()));
        if (sel.followsLatestRun()) {
            props.remove(KEY_RUN_DATE);
            props.remove(KEY_RUN_CYCLE);
        }
        else {
            props.setProperty(KEY_RUN_DATE, sel.runDate().toString());
            props.setProperty(KEY_RUN_CYCLE, String.valueOf(sel.runCycle()));
        }
    }

    // ---- render settings -------------------------------------------------

    public RenderSpec renderSpec() {
        final RenderSpec spec = new RenderSpec();
        try {
            spec.setMaxSize(Integer.parseInt(props.getProperty(KEY_MAX_WIDTH, "1600")),
                            Integer.parseInt(props.getProperty(KEY_MAX_HEIGHT, "1200")));
        }
        catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Ignoring stored output size", e);
        }
        spec.setMercator(Boolean.parseBoolean(props.getProperty(KEY_MERCATOR, "false")));
        try {
            spec.setGribOpacity(Float.parseFloat(props.getProperty(KEY_GRIB_OPACITY, "0.65")));
        }
        catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Ignoring stored GRIB opacity", e);
        }
        final List<String> stored = csv(KEY_LAYERS);
        if (!stored.isEmpty()) {
            for (RenderSpec.LayerKind kind : RenderSpec.LayerKind.values()) {
                spec.setEnabled(kind, stored.contains(kind.name()));
            }
        }
        return spec;
    }

    public void setRenderSpec(RenderSpec spec) {
        props.setProperty(KEY_MAX_WIDTH, String.valueOf(spec.maxWidth()));
        props.setProperty(KEY_MAX_HEIGHT, String.valueOf(spec.maxHeight()));
        props.setProperty(KEY_MERCATOR, String.valueOf(spec.mercator()));
        props.setProperty(KEY_GRIB_OPACITY, String.valueOf(spec.gribOpacity()));
        props.setProperty(KEY_LAYERS, join(spec.layers().stream().map(Enum::name).toList()));
    }

    // ---- output ---------------------------------------------------------

    /** Where composited PNGs are written. Defaults to {@code ~/.weathermap/maps}. */
    public Path outputDir() {
        final String s = props.getProperty(KEY_OUTPUT_DIR);
        return (s == null || s.isBlank())
                ? file.getParent().resolve("maps")
                : Path.of(s);
    }

    public void setOutputDir(Path dir) {
        props.setProperty(KEY_OUTPUT_DIR, dir.toString());
    }

    // ---- helpers --------------------------------------------------------

    private List<String> csv(String key) {
        final String s = props.getProperty(key, "");
        if (s.isBlank()) return List.of();
        final List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            final String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String join(List<String> values) {
        return String.join(",", values);
    }
}
