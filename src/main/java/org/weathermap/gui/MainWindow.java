package org.weathermap.gui;

import org.weathermap.MapService;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribLevel;
import org.weathermap.model.GribSelection;
import org.weathermap.model.Preferences;
import org.weathermap.model.RenderSpec;
import org.weathermap.model.Theme;
import org.weathermap.model.UiLayout;

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
import javax.swing.ButtonGroup;
import javax.swing.JProgressBar;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
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

    private static final java.time.format.DateTimeFormatter CHART_TIME =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm");

    private final Preferences preferences = new Preferences();
    private final MapService service = new MapService();

    private final MapPanel mapPanel;
    private final BaseMapLoader baseMap = new BaseMapLoader(new org.weathermap.osm.OverpassClient());
    private final AreaPanel areaPanel = new AreaPanel();
    private final DataPanel dataPanel = new DataPanel();
    private final JLabel status = new JLabel(" ");
    private final JLabel mapStatus = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();
    private final JToggleButton selectMode = new JToggleButton("Select area");
    private final JToggleButton showResult = new JToggleButton("Show weather map");

    /** The map against the controls. */
    private JSplitPane mainSplit;

    /** The area controls against the GRIB controls. */
    private JSplitPane sideSplit;

    private final JButton previousChart = new JButton("◀");
    private final JButton nextChart = new JButton("▶");
    private final JButton copyChart = new JButton("Copy");
    private final JLabel chartPosition = new JLabel(" ");

    /** The composited series, in time order, and which one is on screen. */
    private List<MapService.Result> results = List.of();
    private int resultIndex;

    private BoundingBox area;
    private RenderSpec renderSpec;
    private DownloadWorker worker;

    public MainWindow() {
        super("Weather Map");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        area = preferences.area();
        renderSpec = preferences.renderSpec();

        mapPanel = new MapPanel(renderSpec, area);
        mapPanel.setSelection(area);
        mapPanel.setFeatures(baseMap.features());

        areaPanel.setArea(area);
        dataPanel.setSelection(preferences.selection());

        areaPanel.setListener(bbox -> {
            setArea(bbox);
            mapPanel.setSelection(bbox);
            mapPanel.showArea(bbox);
        });
        mapPanel.setSelectionListener(bbox -> {
            setArea(bbox);
            areaPanel.setArea(bbox);
            showResult.setSelected(false);
        });
        mapPanel.setViewListener(baseMap::viewChanged);

        baseMap.setOnLoaded(mapPanel::setFeatures);
        baseMap.setOnStatus(mapStatus::setText);

        bindSeriesKeys();
        setJMenuBar(buildMenuBar());
        setContentPane(buildContent());
        restoreLayout(preferences.uiLayout());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                savePreferences();
                baseMap.dispose();
                dispose();
            }
        });

        setStatus(preferences.hasArea()
                ? "Restored the last area: " + area
                : "No stored area - showing the default. Shift-drag on the map to choose one.");
        // Deferred so the panel has a size, which is what decides the view.
        SwingUtilities.invokeLater(() -> baseMap.viewChanged(mapPanel.viewBounds()));
    }

    // ---- layout ---------------------------------------------------------

    private JPanel buildContent() {
        // A split rather than a stack, so the area controls and the GRIB
        // controls can be sized against each other. The variable and level
        // lists are the reason: working through them is easier with the panel
        // dragged tall, and before this their height was whatever the layout
        // decided.
        sideSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                                   scrolled(areaPanel), scrolled(dataPanel));
        sideSplit.setResizeWeight(0.0);          // extra height goes to the lists
        sideSplit.setBorder(BorderFactory.createEmptyBorder());

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, mapPanel, sideSplit);
        mainSplit.setResizeWeight(1.0);          // extra width goes to the map
        mainSplit.setBorder(BorderFactory.createEmptyBorder());

        final JPanel root = new JPanel(new BorderLayout());
        root.add(buildToolBar(), BorderLayout.NORTH);
        root.add(mainSplit, BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        return root;
    }

    private static JScrollPane scrolled(JPanel panel) {
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        final JScrollPane pane = new JScrollPane(panel);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.getVerticalScrollBar().setUnitIncrement(16);
        return pane;
    }

    // ---- remembering the window -----------------------------------------

    /**
     * Opens at the size, position and proportions of the last run.
     *
     * <p>Dividers are set after the frame has its size, because
     * {@link JSplitPane#setDividerLocation(int)} on a pane of zero width is
     * silently ignored - which is why doing this in the constructor appears to
     * work and then does not.</p>
     */
    private void restoreLayout(UiLayout layout) {
        setPreferredSize(new Dimension(layout.width(), layout.height()));
        pack();

        if (layout.hasPosition() && isOnAScreen(layout)) setLocation(layout.x(), layout.y());
        else setLocationRelativeTo(null);

        SwingUtilities.invokeLater(() -> {
            if (layout.mainDivider() > 0) mainSplit.setDividerLocation(layout.mainDivider());
            if (layout.sideDivider() > 0) sideSplit.setDividerLocation(layout.sideDivider());
        });
    }

    /**
     * True when the stored position is still on a display.
     *
     * <p>Monitors get unplugged. A window restored to where a second screen used
     * to be opens somewhere the user cannot see or reach, and the application
     * looks like it failed to start.</p>
     */
    private boolean isOnAScreen(UiLayout layout) {
        final java.awt.Rectangle window =
                new java.awt.Rectangle(layout.x(), layout.y(), layout.width(), layout.height());
        for (java.awt.GraphicsDevice screen
                : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (screen.getDefaultConfiguration().getBounds().intersects(window)) return true;
        }
        LOG.fine(() -> "Stored window position " + layout + " is off-screen; centring instead");
        return false;
    }

    /**
     * The layout as it stands, or the stored one where it cannot be read.
     *
     * <p>A maximised window reports the size of the screen. Saving that means
     * un-maximising restores to full screen and the size the user actually
     * chose is gone, so the previous size is kept and only the dividers are
     * taken.</p>
     */
    private UiLayout currentLayout() {
        UiLayout layout = preferences.uiLayout();
        if (getExtendedState() == JFrame.NORMAL) {
            layout = layout.withWindow(getX(), getY(), getWidth(), getHeight());
        }
        return layout.withDividers(
                mainSplit == null ? UiLayout.UNSET : mainSplit.getDividerLocation(),
                sideSplit == null ? UiLayout.UNSET : sideSplit.getDividerLocation());
    }

    private JPanel buildToolBar() {
        final JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        selectMode.setToolTipText("Drag on the map to choose the area "
                + "(shift-drag does this in either mode)");
        selectMode.addActionListener(e -> mapPanel.setMode(
                selectMode.isSelected() ? MapPanel.Mode.SELECT : MapPanel.Mode.PAN));
        bar.add(selectMode);

        bar.add(Box.createHorizontalStrut(6));
        final JButton zoomToArea = new JButton("Zoom to area");
        zoomToArea.setToolTipText("Frame the selected rectangle");
        zoomToArea.addActionListener(e -> mapPanel.showArea(mapPanel.selection()));
        bar.add(zoomToArea);

        bar.add(Box.createHorizontalStrut(6));
        final JButton loadDetail = new JButton("Load detail");
        loadDetail.setToolTipText("Fetch OSM coastline and place names for the visible area now");
        loadDetail.addActionListener(e -> baseMap.loadNow(mapPanel.viewBounds()));
        bar.add(loadDetail);

        bar.add(Box.createHorizontalStrut(18));
        final JButton download = new JButton("Download and composite");
        download.addActionListener(e -> startDownload());
        bar.add(download);

        bar.add(Box.createHorizontalStrut(8));
        final JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            if (worker != null) worker.cancel(true);
        });
        bar.add(cancel);

        bar.add(Box.createHorizontalStrut(8));
        showResult.setEnabled(false);
        showResult.setToolTipText("Flip between the base map and the last composited map");
        showResult.addActionListener(e -> mapPanel.setShowingResult(showResult.isSelected()));
        bar.add(showResult);

        bar.add(Box.createHorizontalStrut(18));
        bar.add(buildSeriesControls());

        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    /**
     * Stepping through a series, one chart at a time.
     *
     * <p>A series is read by flicking back and forth across a step or two -
     * a front's arrival is obvious in the difference between two charts and
     * nearly invisible in either one alone - so the buttons sit on the toolbar
     * rather than in a menu, and the arrow keys do the same thing.</p>
     */
    private JPanel buildSeriesControls() {
        final JPanel group = new JPanel();
        group.setLayout(new BoxLayout(group, BoxLayout.X_AXIS));

        previousChart.setToolTipText("The previous chart in the series (Left arrow)");
        previousChart.addActionListener(e -> showChart(resultIndex - 1));
        nextChart.setToolTipText("The next chart in the series (Right arrow)");
        nextChart.addActionListener(e -> showChart(resultIndex + 1));

        chartPosition.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        copyChart.setToolTipText(
                "Copy the chart on screen to the clipboard (Ctrl-Shift-C)");
        copyChart.addActionListener(e -> copyCurrentChart());

        group.add(previousChart);
        group.add(chartPosition);
        group.add(nextChart);
        group.add(Box.createHorizontalStrut(12));
        group.add(copyChart);

        updateSeriesControls();
        return group;
    }

    private JPanel buildStatusBar() {
        final JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(200, 16));
        mapStatus.setForeground(new java.awt.Color(90, 90, 90));
        bar.add(status, BorderLayout.CENTER);

        final JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(mapStatus);
        right.add(Box.createHorizontalStrut(10));
        right.add(progress);
        bar.add(right, BorderLayout.EAST);
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
            baseMap.dispose();
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

        final JMenu chart = new JMenu("Chart");
        final JMenuItem copyItem = new JMenuItem("Copy chart image");
        // Ctrl-Shift-C rather than Ctrl-C: a menu accelerator fires wherever the
        // focus is, and taking Ctrl-C would break copying out of the latitude
        // and longitude fields.
        copyItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_C,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
                        | java.awt.event.InputEvent.SHIFT_DOWN_MASK));
        copyItem.addActionListener(e -> copyCurrentChart());
        chart.add(copyItem);
        chart.addSeparator();
        final JMenuItem timeZone = new JMenuItem("Time zone…");
        timeZone.addActionListener(e -> chooseTimeZone());
        chart.add(timeZone);
        bar.add(chart);

        final JMenu appearance = new JMenu("Appearance");
        final ButtonGroup themes = new ButtonGroup();
        for (Theme theme : Theme.values()) {
            final JRadioButtonMenuItem item =
                    new JRadioButtonMenuItem(theme.displayName(), theme == Themes.current());
            item.addActionListener(e -> {
                Themes.apply(theme);
                // Saved now rather than on close: someone who switches theme and
                // then kills the window has still expressed a preference.
                preferences.setTheme(theme);
                preferences.save();
                setStatus(theme.displayName() + " theme");
            });
            themes.add(item);
            appearance.add(item);
        }
        bar.add(appearance);

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
        setStatus("Area: " + bbox + String.format("  (%.2f° × %.2f°)",
                bbox.widthDegrees(), bbox.heightDegrees()));
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
        // Checked here rather than in the panel, because it is only wrong in
        // combination: each variable and each level is individually fine, and
        // it is the pairing that matches nothing.
        final List<String> problems = selection.problems();
        if (!problems.isEmpty() && !resolveLevelProblems(selection, problems)) return;

        final BoundingBox typed = areaPanel.area();
        if (typed != null) area = typed;

        // Save before the work starts, not after: a download that is cancelled
        // or fails still represents what the user asked for, and that is what
        // the CLI should repeat. The series is stored as its definition, so what
        // is saved is "every three hours for two days" and not the particular
        // hours this run happens to resolve to.
        savePreferences(selection);

        // Resolved once, here, on the copy that is actually run.
        final GribSelection running = selection.copy();
        running.applySeries(java.time.ZonedDateTime.now());
        if (running.hasSeries()) {
            setStatus("Series: " + running.forecastHours().size() + " charts, f"
                    + running.forecastHours().get(0) + " to f"
                    + running.forecastHours().get(running.forecastHours().size() - 1));
        }

        progress.setIndeterminate(true);
        progress.setVisible(true);
        worker = new DownloadWorker(area, running, renderSpec.copy(), preferences.outputDir());
        worker.execute();
    }

    /**
     * Offers to add the levels the selected variables are actually published at.
     *
     * <p>Asks rather than silently repairing, because the desktop user can see
     * the lists and a selection that changes itself underneath them is worse
     * than one that explains itself. The command-line tool takes the opposite
     * view for the opposite reason - nobody is watching it.</p>
     *
     * <p>"Download anyway" is offered because this catalogue is not
     * authoritative: it knows the common levels, not every level every model
     * publishes, and being wrong about what exists must never be able to stop a
     * download that would have worked.</p>
     *
     * @return {@code true} if the download should go ahead
     */
    private boolean resolveLevelProblems(GribSelection selection, List<String> problems) {
        final StringBuilder message = new StringBuilder(
                "<html><body style='width: 340px'>"
                + "<p>This selection will match no records:</p><ul>");
        for (String problem : problems) {
            message.append("<li>").append(problem).append("</li>");
        }
        message.append("</ul><p>NOMADS filters every variable by the same set of "
                + "levels, so one variable at a level it is not published at "
                + "empties the whole request - not just its own part of it.</p>"
                + "</body></html>");

        final Object[] choices = {"Add the levels", "Download anyway", "Cancel"};
        final int choice = JOptionPane.showOptionDialog(
                this, message.toString(), "Levels needed",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, choices, choices[0]);

        if (choice != 0) {
            // Anything but an explicit "anyway" - Cancel, or the dialog being
            // closed - stops here.
            return choice == 1;
        }

        final List<GribLevel> added = selection.addMissingLevels();
        dataPanel.setSelection(selection);
        final StringBuilder names = new StringBuilder();
        for (GribLevel level : added) {
            if (names.length() > 0) names.append(", ");
            names.append(level.displayName());
        }
        setStatus(added.isEmpty() ? "No level could be added automatically"
                                  : "Added " + names + " to the selection");
        return true;
    }

    /**
     * Chooses the zone the chart's times are written in.
     *
     * <p>Editable, because the list has six hundred entries and typing
     * "Asia/Ban" finds one faster than scrolling ever will. Whatever is typed is
     * checked before it is accepted: an unknown zone would otherwise be stored,
     * fail to parse on the next run, and silently revert - which looks like the
     * setting not working rather than the name being wrong.</p>
     */
    private void chooseTimeZone() {
        final List<String> ids = new java.util.ArrayList<>(java.time.ZoneId.getAvailableZoneIds());
        java.util.Collections.sort(ids);

        final javax.swing.JComboBox<String> box =
                new javax.swing.JComboBox<>(ids.toArray(new String[0]));
        box.setEditable(true);
        box.setSelectedItem(renderSpec.zone().getId());

        final Object[] message = {
            "Times on the chart are written in this zone.",
            "The system default is " + java.time.ZoneId.systemDefault().getId() + ".",
            " ",
            box
        };
        final int choice = JOptionPane.showConfirmDialog(
                this, message, "Chart time zone",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;

        final String id = String.valueOf(box.getSelectedItem()).trim();
        try {
            renderSpec.setZone(java.time.ZoneId.of(id));
            savePreferences();
            setStatus("Chart times in " + renderSpec.zone().getId()
                    + " - re-download to see it");
        }
        catch (java.time.DateTimeException e) {
            JOptionPane.showMessageDialog(this,
                    "\"" + id + "\" is not a time zone this machine knows.\n\n"
                    + "Use an IANA identifier such as Asia/Bangkok, Europe/London or UTC.",
                    "Unknown time zone", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * Left and right step the series while the map has focus.
     *
     * <p>Bound on the map rather than on the window, because a binding that
     * fires wherever the focus is would take the arrow keys away from the
     * latitude and longitude fields, where they move the caret.</p>
     */
    private void bindSeriesKeys() {
        final javax.swing.InputMap keys = mapPanel.getInputMap(JPanel.WHEN_FOCUSED);
        keys.put(javax.swing.KeyStroke.getKeyStroke("LEFT"), "previousChart");
        keys.put(javax.swing.KeyStroke.getKeyStroke("RIGHT"), "nextChart");
        mapPanel.getActionMap().put("previousChart", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                showChart(resultIndex - 1);
            }
        });
        mapPanel.getActionMap().put("nextChart", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                showChart(resultIndex + 1);
            }
        });
    }

    // ---- the series on screen -------------------------------------------

    /**
     * Puts one chart of the series on the map.
     *
     * <p>Out-of-range indices are ignored rather than clamped, so holding an
     * arrow key at the end of the series does nothing instead of flickering
     * against the last chart.</p>
     */
    private void showChart(int index) {
        if (index < 0 || index >= results.size()) return;
        resultIndex = index;

        final MapService.Result result = results.get(index);
        mapPanel.setResult(result.image(), renderSpec.projectionFor(area));
        showResult.setEnabled(true);
        showResult.setSelected(true);
        updateSeriesControls();
        setStatus(chartDescription(result));
    }

    private String chartDescription(MapService.Result result) {
        if (result.validTime() == null) return String.valueOf(result.pngFile().getFileName());
        return "Valid " + CHART_TIME.withZone(renderSpec.zone()).format(result.validTime())
                + "  ·  " + result.pngFile().getFileName();
    }

    private void updateSeriesControls() {
        final boolean many = results.size() > 1;
        previousChart.setEnabled(many && resultIndex > 0);
        nextChart.setEnabled(many && resultIndex < results.size() - 1);
        copyChart.setEnabled(!results.isEmpty());
        chartPosition.setText(results.isEmpty() ? "—"
                : (resultIndex + 1) + " / " + results.size());
    }

    /**
     * Copies the chart on screen to the system clipboard.
     *
     * <p>The image rather than the file: a chart is usually wanted in a message
     * or a document, and a path to a PNG in {@code ~/.weathermap/maps} is one
     * more step for whoever receives it.</p>
     */
    private void copyCurrentChart() {
        if (results.isEmpty()) {
            setStatus("No chart to copy - download one first");
            return;
        }
        final java.awt.image.BufferedImage image = results.get(resultIndex).image();
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new ImageTransferable(image), null);
            setStatus("Chart copied to the clipboard ("
                    + image.getWidth() + "×" + image.getHeight() + ")");
        }
        catch (IllegalStateException e) {
            // Another application can hold the clipboard; X11 makes this
            // commoner than it sounds.
            LOG.log(Level.WARNING, "Clipboard unavailable", e);
            setStatus("The clipboard is busy - try again");
        }
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
        preferences.setUiLayout(currentLayout());
        preferences.setTheme(Themes.current());
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
                MainWindow.this.results = results;
                MainWindow.this.resultIndex = 0;
                showChart(0);
                setStatus(results.size() == 1
                        ? "Wrote 1 map to " + outputDir
                        : "Wrote " + results.size() + " maps to " + outputDir
                                + " - step through them with ◀ and ▶");
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
        SwingUtilities.invokeLater(() -> {
            // Before the first component exists. A look and feel installed after
            // the window is built themes only what is created afterwards, which
            // shows up as a correctly themed dialog over a Metal window.
            Themes.install(new Preferences().theme());
            new MainWindow().setVisible(true);
        });
    }
}
