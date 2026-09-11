package org.weathermap.gui;

import org.weathermap.MapService;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribSelection;
import org.weathermap.model.Preferences;
import org.weathermap.model.RenderSpec;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The application window: a map on the left, the selection controls on the
 * right, a toolbar above and a status line below.
 *
 * <p>Owns the {@link Preferences} instance, and writes it on every successful
 * download and again on close - so the command-line tool always has the last
 * thing the user actually asked for, not the last thing they typed.</p>
 */
public final class MainWindow extends JFrame {

    private static final Logger LOG = Logger.getLogger(MainWindow.class.getName());

    private final Preferences preferences = new Preferences();
    private final MapService service = new MapService();

    private final MapPanel mapPanel = new MapPanel();
    private final AreaPanel areaPanel = new AreaPanel();
    private final DataPanel dataPanel = new DataPanel();
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();

    private BoundingBox area;
    private RenderSpec renderSpec;
    private DownloadWorker worker;

    public MainWindow() {
        super("Weather Map");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        area = preferences.area();
        renderSpec = preferences.renderSpec();

        areaPanel.setArea(area);
        dataPanel.setSelection(preferences.selection());
        mapPanel.setProjectionOnly(renderSpec, area);

        areaPanel.setListener(this::setArea);
        mapPanel.setSelectionListener(bbox -> {
            setArea(bbox);
            areaPanel.setArea(bbox);
        });

        setJMenuBar(buildMenuBar());
        setContentPane(buildContent());
        setPreferredSize(new Dimension(1280, 800));
        pack();
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                savePreferences();
                dispose();
            }
        });

        setStatus(preferences.hasArea()
                ? "Restored the last area: " + area
                : "No stored area - showing the default. Drag on the map to choose one.");
    }

    // ---- layout ---------------------------------------------------------

    private JPanel buildContent() {
        final JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        side.add(areaPanel);
        side.add(Box.createVerticalStrut(8));
        side.add(dataPanel);
        side.add(Box.createVerticalGlue());

        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                                                mapPanel, new JScrollPane(side));
        split.setResizeWeight(1.0);

        final JPanel root = new JPanel(new BorderLayout());
        root.add(buildToolBar(), BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildToolBar() {
        final JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        final JButton download = new JButton("Download and composite");
        download.addActionListener(e -> startDownload());
        bar.add(download);

        bar.add(Box.createHorizontalStrut(8));
        final JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            if (worker != null) worker.cancel(true);
        });
        bar.add(cancel);

        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    private JPanel buildStatusBar() {
        final JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(200, 16));
        bar.add(status, BorderLayout.CENTER);
        bar.add(progress, BorderLayout.EAST);
        return bar;
    }

    private JMenuBar buildMenuBar() {
        final JMenuBar bar = new JMenuBar();

        final JMenu file = new JMenu("File");
        final JMenuItem outputDir = new JMenuItem("Set output folder…");
        outputDir.addActionListener(e -> chooseOutputDir());
        file.add(outputDir);
        file.addSeparator();
        final JMenuItem quit = new JMenuItem("Quit");
        quit.addActionListener(e -> {
            savePreferences();
            dispose();
        });
        file.add(quit);
        bar.add(file);

        final JMenu view = new JMenu("Layers");
        for (RenderSpec.LayerKind kind : RenderSpec.LayerKind.values()) {
            final JCheckBoxMenuItem item =
                    new JCheckBoxMenuItem(prettify(kind), renderSpec.isEnabled(kind));
            item.addActionListener(e -> {
                renderSpec.setEnabled(kind, item.isSelected());
                setStatus("Layer " + prettify(kind)
                        + (item.isSelected() ? " on" : " off") + " - re-download to see it");
            });
            view.add(item);
        }
        view.addSeparator();
        final JCheckBoxMenuItem mercator =
                new JCheckBoxMenuItem("Mercator projection", renderSpec.mercator());
        mercator.addActionListener(e -> renderSpec.setMercator(mercator.isSelected()));
        view.add(mercator);
        bar.add(view);

        final JMenu help = new JMenu("Help");
        final JMenuItem about = new JMenuItem("About");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Weather Map\n\n"
                        + "Base map: OpenStreetMap via Overpass (ODbL)\n"
                        + "Forecast data: NOAA NOMADS\n"
                        + "GRIB decoder: " + service.reader().description(),
                "About", JOptionPane.INFORMATION_MESSAGE));
        help.add(about);
        bar.add(help);

        return bar;
    }

    private static String prettify(RenderSpec.LayerKind kind) {
        final String s = kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- actions --------------------------------------------------------

    private void setArea(BoundingBox bbox) {
        this.area = bbox;
        mapPanel.setProjectionOnly(renderSpec, bbox);
        setStatus("Area: " + bbox);
    }

    private void chooseOutputDir() {
        final JFileChooser chooser = new JFileChooser(preferences.outputDir().toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Where should composited PNGs be written?");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            preferences.setOutputDir(chooser.getSelectedFile().toPath());
            preferences.save();
            setStatus("Output folder: " + preferences.outputDir());
        }
    }

    private void startDownload() {
        if (worker != null && !worker.isDone()) {
            setStatus("A download is already running");
            return;
        }
        final GribSelection selection = dataPanel.selection();
        if (selection == null) {
            setStatus("Fix the data selection first");
            return;
        }
        final BoundingBox typed = areaPanel.area();
        if (typed != null) area = typed;

        // Save before the work starts, not after: a download that is cancelled
        // or fails still represents what the user asked for, and that is what
        // the CLI should repeat.
        savePreferences(selection);

        progress.setIndeterminate(true);
        progress.setVisible(true);
        worker = new DownloadWorker(area, selection, renderSpec.copy(), preferences.outputDir());
        worker.execute();
    }

    private void savePreferences() {
        final GribSelection selection = dataPanel.selection();
        savePreferences(selection);
    }

    private void savePreferences(GribSelection selection) {
        final BoundingBox typed = areaPanel.area();
        preferences.setArea(typed != null ? typed : area);
        if (selection != null) preferences.setSelection(selection);
        preferences.setRenderSpec(renderSpec);
        preferences.save();
    }

    private void setStatus(String message) {
        status.setText(message);
    }

    // ---- the worker -----------------------------------------------------

    /**
     * Runs {@link MapService} off the EDT.
     *
     * <p>{@code done()} always calls {@code get()}, even when the result is not
     * needed, because that is the only way a background exception surfaces -
     * a {@link SwingWorker} that never asks swallows the cause and leaves the UI
     * saying nothing happened.</p>
     */
    private final class DownloadWorker extends SwingWorker<List<MapService.Result>, String> {

        private final BoundingBox bbox;
        private final GribSelection selection;
        private final RenderSpec spec;
        private final Path outputDir;

        DownloadWorker(BoundingBox bbox, GribSelection selection, RenderSpec spec, Path outputDir) {
            this.bbox = bbox;
            this.selection = selection;
            this.spec = spec;
            this.outputDir = outputDir;
        }

        @Override
        protected List<MapService.Result> doInBackground() throws Exception {
            return service.run(bbox, selection, spec, outputDir, new MapService.Progress() {
                @Override
                public void stage(String message) { publish(message); }

                @Override
                public boolean isCancelled() { return DownloadWorker.this.isCancelled(); }
            });
        }

        @Override
        protected void process(List<String> chunks) {
            setStatus(chunks.get(chunks.size() - 1));
        }

        @Override
        protected void done() {
            progress.setVisible(false);
            progress.setIndeterminate(false);
            try {
                final List<MapService.Result> results = get();
                if (results.isEmpty()) {
                    setStatus("Nothing was rendered - the request matched no GRIB records");
                    return;
                }
                final MapService.Result first = results.get(0);
                mapPanel.setImage(first.image(), spec.projectionFor(bbox));
                setStatus("Wrote " + results.size() + " map(s) to " + outputDir);
            }
            catch (java.util.concurrent.CancellationException e) {
                setStatus("Cancelled");
            }
            catch (Exception e) {
                LOG.log(Level.SEVERE, "Download failed", e);
                setStatus("Failed: " + e.getMessage());
                JOptionPane.showMessageDialog(MainWindow.this,
                        String.valueOf(e.getMessage()), "Download failed",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** Shows the window on the EDT. */
    public static void launch() {
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }
}
