package org.weathermap.gui;

import org.weathermap.MapService;
import org.weathermap.model.BoundingBox;
import org.weathermap.model.GribLevel;
import org.weathermap.model.GribSelection;
import org.weathermap.model.Preferences;
import org.weathermap.model.RenderSpec;
import org.weathermap.model.Theme;
import org.weathermap.model.UiLayout;
import org.weathermap.osm.NearestPlace;
import org.weathermap.tide.TidePoint;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JEditorPane;
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
import java.awt.Desktop;
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

    private static final String WEATHER_PAGE = "https://www.facebook.com/saigonweather/";

    /** From the jar manifest; empty in a development run from classes. */
    private static final String VERSION = java.util.Objects.requireNonNullElse(
            MainWindow.class.getPackage().getImplementationVersion(), "");

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

    private final org.weathermap.model.ProfileStore profiles =
            new org.weathermap.model.ProfileStore();

    /** The profile last saved or recalled, offered as the default next time. */
    private String lastProfileName;

    /** The composited series, in time order, and which one is on screen. */
    private List<MapService.Result> results = List.of();
    private int resultIndex;

    private BoundingBox area;
    private RenderSpec renderSpec;
    private DownloadWorker worker;

    /**
     * The Appearance menu's radio buttons, kept so the Preferences dialog can
     * move the dot when it changes the theme.
     *
     * <p>The theme is deliberately in both places - see
     * {@link PreferencesDialog} - and the price of that is this: two controls
     * for one setting have to agree, or the menu quietly claims the old theme
     * is still current.</p>
     */
    private final java.util.Map<Theme, JRadioButtonMenuItem> themeItems =
            new java.util.EnumMap<>(Theme.class);

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
        mapPanel.setPickCancelledListener(() -> setStatus("Tide chart cancelled"));

        baseMap.setOnLoaded(mapPanel::setFeatures);
        baseMap.setOnStatus(mapStatus::setText);

        setIconImages(appIcons());
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

    /**
     * The About box.
     *
     * <p>Rendered as HTML in a pane rather than as a plain message, so the
     * weather page is a link someone can click instead of a URL they have to
     * retype. The licence line is here because section 5 of the GPL asks an
     * interactive program to carry the notice where a user will meet it.</p>
     */
    private void showAbout() {
        final JEditorPane pane = new JEditorPane("text/html",
                aboutHtml(VERSION, service.reader().description()));
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.addHyperlinkListener(event -> {
            if (event.getEventType() != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                return;
            }
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(java.net.URI.create(WEATHER_PAGE));
                }
            }
            catch (Exception e) {
                // A missing browser is not worth a second dialog.
                LOG.log(Level.FINE, "Could not open " + WEATHER_PAGE, e);
            }
        });

        JOptionPane.showMessageDialog(this, pane, "About",
                JOptionPane.INFORMATION_MESSAGE, iconAt(64));
    }

    /**
     * The About text.
     *
     * <p>Separate from the dialog so it can be asserted. What it says is not
     * decoration: the attribution and the licence notice are the two things a
     * released build is expected to carry, and a silent edit that dropped
     * either would be invisible until someone looked.</p>
     */
    static String aboutHtml(String version, String decoder) {
        return """
                <html><body style="font-family: sans-serif; font-size: 10pt; width: 360px">
                <p style="font-size: 13pt"><b>Weather Map%s</b></p>
                <p>Application by Saigon Weather &copy; Del 2026.<br>
                Our weather page:
                <a href="%s">facebook.com/saigonweather</a></p>
                <p>Licensed under the GNU General Public License, version 3 or
                later. This program comes with <b>absolutely no warranty</b>.
                See Help &gt; User's Guide &gt; License.</p>
                <p style="color: #777">
                Base map: OpenStreetMap via Overpass (ODbL)<br>
                Place names: Natural Earth (public domain)<br>
                Forecast data: NOAA NOMADS<br>
                GRIB decoder: %s</p>
                </body></html>
                """.formatted(version.isEmpty() ? "" : " " + version, WEATHER_PAGE, decoder);
    }

    private static javax.swing.ImageIcon iconAt(int size) {
        final java.net.URL url =
                MainWindow.class.getResource("/icons/weathermap-" + size + ".png");
        return url == null ? null : new javax.swing.ImageIcon(url);
    }

    /**
     * The window icon, at every size the toolkit might ask for.
     *
     * <p>A list rather than one image: the title bar, the task switcher and the
     * dock want different sizes, and handing over a single large one leaves the
     * toolkit to downscale it badly at 16 pixels. Missing icons are skipped
     * rather than failing - a window without an icon is a smaller problem than
     * a window that will not open.</p>
     */
    static List<java.awt.Image> appIcons() {
        final List<java.awt.Image> icons = new java.util.ArrayList<>();
        for (int size : new int[]{16, 32, 48, 64, 128, 256}) {
            final java.net.URL url =
                    MainWindow.class.getResource("/icons/weathermap-" + size + ".png");
            if (url != null) icons.add(new javax.swing.ImageIcon(url).getImage());
        }
        if (icons.isEmpty()) LOG.fine("No bundled window icons found");
        return icons;
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
        loadDetail.setToolTipText("Fetch the coastline, boundaries and place names "
                + "the chart would use, for the visible area, now");
        loadDetail.addActionListener(e -> loadDetail());
        bar.add(loadDetail);

        bar.add(Box.createHorizontalStrut(18));
        final JButton saveProfile = new JButton("Save profile");
        saveProfile.setToolTipText("Store the area, model, hours or series, "
                + "variables and levels under a name");
        saveProfile.addActionListener(e -> saveProfile());
        bar.add(saveProfile);

        bar.add(Box.createHorizontalStrut(6));
        final JButton recallProfile = new JButton("Recall profile");
        recallProfile.setToolTipText("Load a saved profile");
        recallProfile.addActionListener(e -> recallProfile());
        bar.add(recallProfile);

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

        final JMenuItem prefs = new JMenuItem("Preferences…");
        prefs.setAccelerator(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_COMMA,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        prefs.addActionListener(e -> showPreferences());
        file.add(prefs);

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

        final JMenu tide = new JMenu("Tide");
        final JMenuItem tideChart = new JMenuItem("Tide chart…");
        tideChart.addActionListener(e -> showTideChart());
        tide.add(tideChart);
        bar.add(tide);

        final JMenu share = new JMenu("Share");
        final JMenuItem instagram = new JMenuItem("Post to Instagram…");
        instagram.addActionListener(e -> postToInstagram());
        share.add(instagram);
        bar.add(share);

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
            themeItems.put(theme, item);
        }
        bar.add(appearance);

        final JMenu help = new JMenu("Help");

        final JMenuItem userGuide = new JMenuItem("User's Guide");
        userGuide.setAccelerator(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_F1, 0));
        userGuide.addActionListener(e -> HelpWindow.showUserGuide(this));
        help.add(userGuide);

        final JMenuItem developerGuide = new JMenuItem("Developer's Guide");
        developerGuide.addActionListener(e -> HelpWindow.showDeveloperGuide(this));
        help.add(developerGuide);
        help.addSeparator();

        final JMenuItem about = new JMenuItem("About");
        about.addActionListener(e -> showAbout());
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
    /**
     * Fetches the geography a chart of this view would be drawn from.
     *
     * <p>The chart's own kinds, so pressing this shows what the chart would
     * show - which is what the button has always claimed and, until recently,
     * did not do.</p>
     *
     * <p>Above a few degrees it asks first. OSM coastline is surveyed rather
     * than drawn for a screen, and thirteen degrees of South-East Asia came
     * back as a hundred and eight megabytes: a minute or two of waiting that,
     * unannounced, reads as the button having done nothing at all.</p>
     */
    private void loadDetail() {
        final BoundingBox view = mapPanel.viewBounds();
        final List<org.weathermap.osm.FeatureKind> kinds =
                MapService.featureKindsFor(renderSpec);

        final String warning = largeDetailWarning(view, kinds);
        if (warning != null && JOptionPane.showConfirmDialog(this, new JLabel(warning),
                "Load map detail?", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
            setStatus("Map detail not loaded");
            return;
        }
        baseMap.loadNow(view, kinds);
    }

    /**
     * Above this span, a coastline query is worth warning about, in degrees.
     *
     * <p>Six is where full-resolution coastline starts costing tens of
     * megabytes. Below it the query is quick enough that asking would be an
     * interruption rather than a kindness.</p>
     */
    static final double LARGE_DETAIL_SPAN = 6.0;

    /**
     * What to say before a large query, or null when it is small enough to
     * just do.
     *
     * <p>Separate and package-private for the same reason the tide
     * confirmation is: the words are the substance.</p>
     */
    static String largeDetailWarning(BoundingBox view,
                                     List<org.weathermap.osm.FeatureKind> kinds) {
        if (!kinds.contains(org.weathermap.osm.FeatureKind.COASTLINE)) return null;
        final double span = Math.max(view.widthDegrees(), view.heightDegrees());
        if (span <= LARGE_DETAIL_SPAN) return null;

        return "<html><body style='width:360px'>"
                + "<p>This view is <b>" + String.format(java.util.Locale.ROOT, "%.0f°", span)
                + " across</b>. Full-resolution OSM coastline for an area that size "
                + "is tens of megabytes and can take a minute or two to arrive.</p>"
                + "<p>It is kept for four weeks afterwards, so this is a one-off "
                + "for this area — but the map will not change until it lands.</p>"
                + "<p>At this scale the bundled world outline is close to "
                + "indistinguishable from it. Zoom in first if you only want "
                + "place names.</p></body></html>";
    }

    /**
     * Starts a tide chart, which begins by asking where.
     *
     * <p>The map, not a dialog full of numbers. A tide belongs to a point and
     * the point that matters is a harbour or an anchorage someone can see on
     * the chart, so the interaction is the one that fits: scroll to it, click
     * it. Storm Glass is not touched until the click has been confirmed,
     * because a stray one would otherwise spend two of a ten-a-day quota on
     * open ocean.</p>
     */
    private void showTideChart() {
        setStatus("Click the point on the map to read the tide at, or press Esc");
        mapPanel.pickPoint(this::tidePointPicked);
    }

    /**
     * Names the clicked point from the map data and asks before fetching.
     *
     * <p>The name is the whole point of the confirmation. A latitude and a
     * longitude are exact and unreadable - 10.35, 107.08 is a harbour, a
     * headland or forty kilometres of open water, and only one of those is
     * worth a tide chart. "Vũng Tàu (town) — 4 km away" is checkable at a
     * glance, and if it says 180 km the click was somewhere nobody meant.</p>
     */
    private void tidePointPicked(double[] at) {
        setStatus(" ");
        final NearestPlace.Match nearest =
                NearestPlace.near(at[0], at[1], baseMap.features(), NearestPlace.NAMEABLE);
        final TidePoint point =
                new TidePoint(nearest == null ? null : nearest.name(), at[0], at[1]);

        if (!confirmTidePoint(point, nearest)) {
            setStatus("Tide chart cancelled");
            return;
        }
        TideWindow.show(this, preferences, renderSpec, point);
    }

    /** @return true when the user confirmed reading the tide at this point */
    private boolean confirmTidePoint(TidePoint point, NearestPlace.Match nearest) {
        final boolean cached = new org.weathermap.tide.StormglassClient(
                preferences::stormglassApiKey).hasCachedForecast(point.lat(), point.lon());

        final int choice = JOptionPane.showConfirmDialog(
                this, new JLabel(tidePointMessage(point, nearest, cached)),
                "Read the tide here?", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        return choice == JOptionPane.OK_OPTION;
    }

    /**
     * What the confirmation actually says.
     *
     * <p>Separate from showing it, and package-private, because the words are
     * the substance and the dialog is only the box they arrive in - this way
     * they can be asserted without a window.</p>
     *
     * @param cached true when the answer is already on disk, so pressing the
     *               button spends nothing
     */
    static String tidePointMessage(TidePoint point, NearestPlace.Match nearest, boolean cached) {
        final StringBuilder said = new StringBuilder("<html><body style='width:340px'>");
        said.append("<p>").append(point.coordinates()).append("</p>");

        if (nearest == null) {
            said.append("<p><b>No named place in the map data is anywhere near.</b> "
                    + "Load map detail for this area to get a better answer.</p>");
        }
        else {
            final boolean bundled =
                    org.weathermap.osm.WorldGazetteer.isBundled(nearest.feature());
            said.append("<p>Nearest place in the ")
                .append(bundled ? "bundled world gazetteer" : "OSM data")
                .append(":<br><b>").append(escape(nearest.describe())).append("</b></p>");

            // Which of the two sets it came from is the difference between "the
            // nearest town really is 66 km away" and "the only names here are
            // the ones shipped with the application". They read identically -
            // a name and a distance - and only one of them is worth doing
            // something about, so it is said rather than left to be inferred
            // from how far away it sounds.
            if (bundled) {
                said.append("<p>That is a major city from the bundled set, not "
                        + "necessarily the nearest place. <b>Load detail</b> to "
                        + "name this point from OSM.</p>");
            }
            else if (nearest.distanceKm() > FAR_ENOUGH_TO_DOUBT_KM) {
                said.append("<p>That is a long way off, so this point is well "
                        + "away from anything named.</p>");
            }
        }

        said.append("<p>").append(cached
                ? "Already fetched today — this costs no requests."
                : "Storm Glass will be asked for "
                        + org.weathermap.tide.StormglassClient.DAYS_AHEAD
                        + " days of tide here, which costs 2 of today's requests.")
            .append("</p></body></html>");
        return said.toString();
    }

    /**
     * Beyond this, the nearest name is telling you about the data rather than
     * about the place. A hundred kilometres is roughly how far apart the
     * bundled gazetteer's cities are in open country.
     */
    static final double FAR_ENOUGH_TO_DOUBT_KM = 100;

    /** A place name is map data, and map data is not HTML. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Opens Preferences and takes up whatever it changed.
     *
     * <p>The dialog writes straight into {@code renderSpec} and the stored
     * preferences, so there is nothing to copy back - but the menu has to be
     * re-pointed at the current theme, and the size and zone are worth saying
     * out loud because neither shows up until the next download.</p>
     */
    private void showPreferences() {
        if (!PreferencesDialog.show(this, preferences, renderSpec)) return;

        final JRadioButtonMenuItem item = themeItems.get(Themes.current());
        if (item != null) item.setSelected(true);

        setStatus(String.format("Preferences saved - charts %d×%d in %s",
                renderSpec.maxWidth(), renderSpec.maxHeight(), renderSpec.zone().getId()));
    }

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

    // ---- posting ---------------------------------------------------------

    /**
     * Posts the chart on screen and the ones after it, as a carousel.
     *
     * <p>Forwards only, and in time order. A carousel is read left to right, so
     * a post that began at the current chart and wrapped round to the start of
     * the series would put next week between two Fridays.</p>
     */
    private void postToInstagram() {
        if (results.isEmpty()) {
            setStatus("Download a chart before posting");
            return;
        }
        final int available = results.size() - resultIndex;
        if (available < 2) {
            JOptionPane.showMessageDialog(this,
                    "A carousel needs at least two charts, and this is the last one.\n\n"
                    + "Step back, or download a series that runs further ahead.",
                    "Not enough charts", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        final org.weathermap.model.InstagramAccount stored =
                org.weathermap.model.InstagramAccount
                        .load(org.weathermap.model.InstagramAccount.defaultFile())
                        .orElse(null);

        InstagramDialog.ask(this, stored, available, renderSpec.zone()).ifPresent(request -> {
            try {
                // Saved before posting, so a failure does not also lose the
                // settings the user has just typed in.
                request.account().save(
                        org.weathermap.model.InstagramAccount.defaultFile());
            }
            catch (java.io.IOException e) {
                LOG.log(Level.WARNING, "Could not store the Instagram account", e);
            }
            new PostWorker(request).execute();
        });
    }

    /** Writes the images and posts them, off the event thread. */
    /** What a finished post produced: the media, and where to look at it. */
    private record Posted(String mediaId, String permalink, int charts) { }

    private final class PostWorker extends SwingWorker<Posted, String> {

        private final InstagramDialog.Request request;

        PostWorker(InstagramDialog.Request request) {
            this.request = request;
            progress.setIndeterminate(true);
            progress.setVisible(true);
        }

        @Override
        protected Posted doInBackground() throws Exception {
            final List<MapService.Result> charts =
                    org.weathermap.instagram.ChartPublisher.selectFrom(
                            results, resultIndex, request.chartCount());
            publish("Writing " + charts.size() + " images for Instagram to fetch");

            final var publisher =
                    new org.weathermap.instagram.ChartPublisher(request.account());
            final var images = publisher.publish(charts);

            org.weathermap.instagram.PublishGate.sync(request.account(), this::publish);
            org.weathermap.instagram.PublishGate.awaitReachable(images, this::publish);

            publish("Posting to Instagram");
            final var client =
                    new org.weathermap.instagram.InstagramClient(request.account());
            // Expanded here, not when it was typed and not when it was stored.
            // The account keeps the template - "${tomorrow:'+%A %e %B %Y'}" -
            // so tomorrow's post says tomorrow's date without anyone editing
            // it. The zone is the chart's, so the caption and the labels on the
            // images cannot disagree about what day it is.
            final String caption = org.weathermap.text.CaptionTemplate.expand(
                    request.caption(), java.time.ZonedDateTime.now(renderSpec.zone()),
                    java.util.Locale.getDefault());

            final String mediaId =
                    client.postCarousel(images, caption, this::publish);
            // Asked for here rather than on the event thread: the post has
            // already succeeded and this is one more network round trip.
            return new Posted(mediaId, client.permalink(mediaId).orElse(null),
                              images.size());
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
                announce(get());
            }
            catch (java.util.concurrent.CancellationException e) {
                setStatus("Cancelled");
            }
            catch (Exception e) {
                LOG.log(Level.WARNING, "Instagram post failed", e);
                final Throwable cause = e.getCause() != null ? e.getCause() : e;
                setStatus("Instagram post failed: " + cause.getMessage());
                JOptionPane.showMessageDialog(MainWindow.this,
                        String.valueOf(cause.getMessage()),
                        "Instagram post failed", JOptionPane.ERROR_MESSAGE);
            }
        }

        /**
         * Says so, in a dialog.
         *
         * <p>A failure opened a dialog and a success wrote one line into the
         * status bar. Posting takes minutes - Meta fetches every image before it
         * will publish - so by the time it finishes nobody is watching the
         * status bar, and a post that had worked perfectly looked like nothing
         * had happened at all. Both outcomes are worth interrupting for.</p>
         */
        private void announce(Posted posted) {
            final String where = request.account().profile().isBlank()
                    ? "Instagram" : request.account().profile();
            setStatus("Posted " + posted.charts() + " charts to " + where);

            final String message = "Posted " + posted.charts() + " charts to " + where
                    + ".\n\nMedia ID " + posted.mediaId()
                    + (posted.permalink() == null ? "" : "\n" + posted.permalink());

            if (posted.permalink() == null) {
                JOptionPane.showMessageDialog(MainWindow.this, message,
                        "Posted to Instagram", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            final Object[] options = {"View the post", "Close"};
            final int chosen = JOptionPane.showOptionDialog(MainWindow.this, message,
                    "Posted to Instagram", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
            if (chosen == 0) browse(posted.permalink());
        }
    }

    /** Opens a URL in the desktop browser, quietly if there is none. */
    private void browse(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop()
                            .isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "Could not open " + url, e);
        }
    }

    // ---- profiles --------------------------------------------------------

    /**
     * Stores what is on screen under a name.
     *
     * <p>Saves the selection as the panel reports it rather than the last one
     * downloaded, so a profile can be built and stored without fetching
     * anything - which is the point of having them.</p>
     */
    private void saveProfile() {
        final GribSelection selection = dataPanel.selection();
        if (selection == null) {
            setStatus("Fix the data selection before saving a profile");
            return;
        }
        final BoundingBox typed = areaPanel.area();
        final BoundingBox toSave = (typed != null) ? typed : area;

        final String name = JOptionPane.showInputDialog(this,
                "Name for this area and data selection:",
                lastProfileName == null ? "" : lastProfileName);
        if (name == null || name.isBlank()) return;

        if (profiles.exists(name)
                && JOptionPane.showConfirmDialog(this,
                        "\"" + name.trim() + "\" already exists. Replace it?",
                        "Replace profile", JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            profiles.save(name, toSave, selection);
            lastProfileName = name.trim();
            setStatus("Saved profile \"" + lastProfileName + "\"");
        }
        catch (java.io.IOException e) {
            LOG.log(Level.WARNING, "Could not save profile " + name, e);
            JOptionPane.showMessageDialog(this,
                    "Could not save the profile:\n" + e.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Loads a saved profile into the controls.
     *
     * <p>It fills the panels rather than starting a download: recalling is for
     * getting ready, and someone switching to a stored area usually wants to
     * look at it, or change the hours, before fetching anything.</p>
     */
    private void recallProfile() {
        final List<String> names = profiles.names();
        if (names.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No profiles saved yet.\n\nSet up an area and a data selection, "
                    + "then use Save profile.",
                    "No profiles", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        final javax.swing.JList<String> list =
                new javax.swing.JList<>(names.toArray(new String[0]));
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedValue(lastProfileName, true);
        if (list.getSelectedIndex() < 0) list.setSelectedIndex(0);
        list.setVisibleRowCount(Math.min(12, names.size()));

        final int choice = JOptionPane.showConfirmDialog(this,
                new javax.swing.JScrollPane(list), "Recall profile",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;

        final String name = list.getSelectedValue();
        if (name == null) return;

        profiles.load(name).ifPresentOrElse(profile -> {
            setArea(profile.area());
            areaPanel.setArea(profile.area());
            mapPanel.setSelection(profile.area());
            mapPanel.showArea(profile.area());
            showResult.setSelected(false);
            dataPanel.setSelection(profile.selection());
            lastProfileName = profile.name();
            setStatus("Recalled profile \"" + profile.name() + "\" - " + profile.area());
        }, () -> setStatus("Profile \"" + name + "\" could not be read"));
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
