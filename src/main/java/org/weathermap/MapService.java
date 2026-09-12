package org.weathermap;

import org.weathermap.grib.GribReader;
import org.weathermap.grib.GribReaders;
import org.weathermap.grib.GribSource;
import org.weathermap.grib.Grid;
import org.weathermap.grib.NomadsClient;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribSelection;
import org.weathermap.model.RenderSpec;
import org.weathermap.osm.Feature;
import org.weathermap.osm.FeatureKind;
import org.weathermap.osm.OsmSource;
import org.weathermap.osm.OverpassClient;
import org.weathermap.render.Compositor;
import org.weathermap.render.PngWriter;
import org.weathermap.util.Http;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /** One composited map: the image and where it came from. */
    public record Result(BufferedImage image, Grid primaryGrid, Path pngFile) { }

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

            final BufferedImage image =
                    compositor.render(bbox, features, grids, selection.model().displayName());

            // The same field the chart is titled for, and the same instant it is
            // captioned with. Taking grids.get(0) here instead named the file
            // after a wind component the title never mentions, and stamped it
            // with that component's time rather than the chart's.
            final Grid primary = Compositor.primaryOf(grids);
            final Path png = outputDir.resolve(PngWriter.fileName(
                    selection.model().id(), primary.variable().code(),
                    primary.level().code(), Compositor.validTimeOf(grids)));
            PngWriter.write(image, png);
            results.add(new Result(image, primary, png));
        }
        return results;
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

        // Asked before the request is built, not after it fails. Overpass
        // answers an impossible box by timing out, which costs the full retry
        // budget across every instance - ninety seconds to arrive at the
        // outline we would have chosen instantly.
        if (OverpassClient.isTooLarge(bbox)) {
            LOG.info(() -> String.format(
                    "area spans %.1f deg, beyond the %.0f deg Overpass is worth asking "
                    + "for; using the bundled world outline",
                    Math.max(bbox.widthDegrees(), bbox.heightDegrees()),
                    OverpassClient.MAX_SERVABLE_SPAN));
            p.stage(String.format(
                    "Area wider than %.0f\u00b0 - using the coarse world outline",
                    OverpassClient.MAX_SERVABLE_SPAN));
            return org.weathermap.osm.WorldBaseMap.features();
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
        p.stage("OSM detail unavailable - using the coarse world outline");
        return org.weathermap.osm.WorldBaseMap.features();
    }
}
