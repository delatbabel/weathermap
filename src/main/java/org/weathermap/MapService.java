package org.weathermap;

import org.weathermap.grib.GribReader;
import org.weathermap.grib.GribReaders;
import org.weathermap.grib.GribSource;
import org.weathermap.grib.Grid;
import org.weathermap.grib.NomadsClient;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribSelection;
import org.weathermap.model.GribVariable;
import org.weathermap.model.RenderSpec;
import org.weathermap.osm.Feature;
import org.weathermap.osm.FeatureKind;
import org.weathermap.osm.OsmSource;
import org.weathermap.osm.OverpassClient;
import org.weathermap.osm.WorldGazetteer;
import org.weathermap.render.Compositor;
import org.weathermap.render.PngWriter;
import org.weathermap.util.Http;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The whole job, once: fetch the base map, download the GRIB, decode it,
 * composite, write the PNGs.
 *
 * <p>Both front ends call this and nothing else. The Swing application runs it
 * on a background worker and shows the images; the command-line tool runs it and
 * exits. Keeping the sequence in one place is what makes "the CLI repeats what
 * the GUI last did" true by construction rather than by discipline.</p>
 *
 * <p>Stateless apart from its collaborators, so one instance can serve repeated
 * runs.</p>
 */
public final class MapService {

    private static final Logger LOG = Logger.getLogger(MapService.class.getName());

    /** Coarse steps, for a progress bar that means something. */
    public interface Progress {
        void stage(String message);

        default void bytes(long soFar, long total) { }

        default boolean isCancelled() { return false; }

        Progress SILENT = message -> { };
    }

    private final OsmSource osm;
    private final GribSource grib;
    private final GribReader reader;

    public MapService() {
        this(new OverpassClient(), new NomadsClient(), GribReaders.best());
    }

    public MapService(OsmSource osm, GribSource grib, GribReader reader) {
        this.osm = osm;
        this.grib = grib;
        this.reader = reader;
    }

    public GribReader reader() { return reader; }

    /**
     * One composited map: the image, where it came from, and when it is for.
     *
     * @param validTime the instant the chart depicts, which is not the primary
     *                  grid's own time when that grid is an accumulation
     */
    public record Result(BufferedImage image, Grid primaryGrid, Path pngFile,
                         java.time.Instant validTime) { }

    /**
     * Runs the job.
     *
     * @param outputDir where PNGs are written; one per forecast hour
     * @return one result per forecast hour, in order
     */
    public List<Result> run(BoundingBox bbox, GribSelection selection, RenderSpec spec,
                            Path outputDir, Progress progress)
            throws IOException, InterruptedException {

        final Progress p = (progress == null) ? Progress.SILENT : progress;

        p.stage("Fetching base map from " + osm.description());
        final List<Feature> features = fetchFeatures(bbox, spec, p);

        p.stage("Downloading GRIB from " + grib.description());
        final List<Path> gribFiles = grib.download(bbox, selection, new Http.ProgressListener() {
            @Override
            public void onProgress(long soFar, long total) { p.bytes(soFar, total); }

            @Override
            public boolean isCancelled() { return p.isCancelled(); }
        });

        final Compositor compositor = new Compositor(spec);
        final List<Result> results = new ArrayList<>();

        for (int i = 0; i < gribFiles.size() && !p.isCancelled(); i++) {
            final Path file = gribFiles.get(i);
            final int forecastHour = selection.forecastHours().get(i);
            p.stage("Rendering forecast hour " + forecastHour
                    + " (" + (i + 1) + " of " + gribFiles.size() + ")");

            final List<Grid> grids = reader.read(file);
            if (grids.isEmpty()) {
                LOG.warning("No fields decoded from " + file + "; skipping");
                continue;
            }

            reportMissingFields(selection, grids, forecastHour, p);

            final BufferedImage image =
                    compositor.render(bbox, features, grids, selection.model().displayName());

            // The same field the chart is titled for, and the same instant it is
            // captioned with. Taking grids.get(0) here instead named the file
            // after a wind component the title never mentions, and stamped it
            // with that component's time rather than the chart's.
            final Grid primary = Compositor.primaryOf(grids);
            final java.time.Instant validTime = Compositor.validTimeOf(grids);
            final Path png = outputDir.resolve(PngWriter.fileName(
                    selection.model().id(), primary.variable().code(),
                    primary.level().code(), validTime));
            PngWriter.write(image, png);
            results.add(new Result(image, primary, png, validTime));
        }
        return results;
    }

    /**
     * Says so when a variable that was asked for is not in what came back.
     *
     * <p>A field that is simply absent renders as nothing at all, which is
     * indistinguishable from a field that is present and flat - so a chart
     * missing its precipitation looks like a chart of a dry day. The commonest
     * cause is not an error: accumulated precipitation does not exist at
     * forecast hour 0, because nothing has accumulated yet, and asking for it
     * there is a reasonable thing to do once.</p>
     */
    private void reportMissingFields(GribSelection selection, List<Grid> grids,
                                     int forecastHour, Progress p) {
        final Set<String> decoded = new HashSet<>();
        for (Grid grid : grids) decoded.add(grid.variable().code());

        final List<String> missing = new ArrayList<>();
        for (GribVariable variable : selection.variables()) {
            if (decoded.contains(variable.code())) continue;
            if (variable.pairedCode() != null && decoded.contains(variable.pairedCode())) continue;
            missing.add(variable.displayName());
        }
        if (missing.isEmpty()) return;

        final String names = String.join(", ", missing);
        final String because = (forecastHour == 0 && missing.stream()
                .anyMatch(m -> m.toLowerCase(Locale.ROOT).contains("precipitation")))
                ? " - accumulated fields do not exist at forecast hour 0; ask for 3 or more"
                : " - not published for this model, level or hour";

        LOG.warning("f" + forecastHour + ": no data for " + names + because);
        p.stage("No data for " + names + because);
    }

    /**
     * Fetches only the feature kinds the render will actually draw.
     *
     * <p>A failure here is <b>not</b> fatal: Overpass is a shared public service
     * that rate-limits and occasionally times out, and a weather map with no
     * coastline is still a weather map. The GRIB field is the point; the base map
     * is context. So this logs and returns empty rather than aborting the run.</p>
     */
    private List<Feature> fetchFeatures(BoundingBox bbox, RenderSpec spec, Progress p) {
        final List<FeatureKind> kinds = new ArrayList<>();
        if (spec.isEnabled(RenderSpec.LayerKind.COASTLINE)
                || spec.isEnabled(RenderSpec.LayerKind.LAND_SEA)) {
            kinds.add(FeatureKind.COASTLINE);
        }
        if (spec.isEnabled(RenderSpec.LayerKind.BOUNDARIES)) kinds.add(FeatureKind.BOUNDARY);
        if (spec.isEnabled(RenderSpec.LayerKind.PLACE_LABELS)) kinds.add(FeatureKind.PLACE);
        if (kinds.isEmpty()) return List.of();

        final List<Feature> out = new ArrayList<>(baseMapFor(bbox, kinds, p));

        // The names of the sea come from the bundle at every scale, not only the
        // wide ones. Overpass is never asked for water - the place query wants
        // cities - so without this a marine chart has no name for the thing the
        // wind is blowing across, at any zoom. Country names join them once the
        // chart is wide enough that city names have stopped saying where you
        // are. Both are a few hundred bytes of labels against a shared budget,
        // so they cost the cities almost nothing.
        if (spec.isEnabled(RenderSpec.LayerKind.PLACE_LABELS)) {
            out.addAll(WorldGazetteer.of(FeatureKind.MARINE));
            out.addAll(WorldGazetteer.of(FeatureKind.COUNTRY));
        }
        return out;
    }

    /** Coastline, boundaries and city names - from OSM where it can serve them. */
    private List<Feature> baseMapFor(BoundingBox bbox, List<FeatureKind> kinds, Progress p) {
        // Asked before the request is built, not after it fails. Overpass
        // answers an impossible box by timing out, which costs the full retry
        // budget across every instance - ninety seconds to arrive at the
        // outline we would have chosen instantly.
        if (OverpassClient.isTooLarge(bbox)) {
            LOG.info(() -> String.format(
                    "area spans %.1f deg, beyond the %.0f deg Overpass is worth asking "
                    + "for; using the bundled world outline and gazetteer",
                    Math.max(bbox.widthDegrees(), bbox.heightDegrees()),
                    OverpassClient.MAX_SERVABLE_SPAN));
            p.stage(String.format(
                    "Area wider than %.0f\u00b0 - using the bundled world map",
                    OverpassClient.MAX_SERVABLE_SPAN));
            return bundledBaseMap();
        }

        try {
            final List<Feature> features = osm.fetch(bbox, kinds);
            if (!features.isEmpty()) {
                LOG.info(() -> "base map: " + features.size() + " feature(s) from OSM");
                return features;
            }
            LOG.info("OSM returned nothing here; using the bundled world outline");
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "OSM base map unavailable; falling back to the "
                    + "bundled world outline", e);
        }

        // Coarse, but a coastline is what tells a reader where the weather is,
        // and a chart without one is barely a chart. Natural Earth 1:110m is
        // the wrong resolution for a small area and still far better than an
        // empty background - which is what this used to produce whenever
        // Overpass was busy.
        p.stage("OSM detail unavailable - using the bundled world map");
        return bundledBaseMap();
    }

    /**
     * The bundled outline together with its city names.
     *
     * <p>The names are only reached for here, when OSM is not supplying any.
     * Where OSM has answered, its places are better - they carry real
     * populations and go down to villages - and mixing the two sets would put
     * two dots on some cities and rank them by two different meanings of
     * "important".</p>
     */
    private static List<Feature> bundledBaseMap() {
        final List<Feature> out = new ArrayList<>(org.weathermap.osm.WorldBaseMap.features());
        out.addAll(WorldGazetteer.of(FeatureKind.PLACE));
        return out;
    }
}
