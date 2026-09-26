package org.weathermap.gui;

import org.weathermap.model.BoundingBox;
import org.weathermap.model.Preferences;
import org.weathermap.model.RenderSpec;
import org.weathermap.render.PngWriter;
import org.weathermap.tide.StormglassClient;
import org.weathermap.tide.TideChart;
import org.weathermap.tide.TideData;
import org.weathermap.tide.TidePoint;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The tide chart: a place, a day, and the water either side of it.
 *
 * <h2>The place is chosen before the window opens</h2>
 *
 * <p>Everything else here works on a rectangle; a tide belongs to a spot. So
 * the first step is on the map - scroll to the place, click it, and confirm
 * what the map data says is nearest - and this window opens on the answer and
 * fetches straight away. Opening it first and asking afterwards was the other
 * way round: a window full of controls before the one decision that matters
 * had been made.</p>
 *
 * <p>The coordinates are still fields rather than a readout, for correcting
 * the pick by hand. Storm Glass then answers from the nearest gauge it has and
 * says which, and how far away, because "the tide here" from a station sixty
 * kilometres up the coast is a different claim from one four kilometres
 * away.</p>
 *
 * <h2>Paging days costs nothing</h2>
 *
 * <p>Ten days are fetched at once and kept for a day, so the arrows move
 * through it without touching the network. That is the whole reason the window
 * is built around a day at a time rather than asking per day: the service
 * rations requests, and the free tier would be gone by Thursday.</p>
 */
public final class TideWindow extends JFrame {

    private static final Logger LOG = Logger.getLogger(TideWindow.class.getName());

    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEE d MMM");
    private static final DateTimeFormatter RETRIEVED =
            DateTimeFormatter.ofPattern("HH:mm");

    /** One at a time: a second tide window would fetch the same ten days again. */
    private static TideWindow open;

    private final Preferences preferences;
    private final RenderSpec renderSpec;
    private final StormglassClient client;

    private final JTextField latitude = new JTextField(8);
    private final JTextField longitude = new JTextField(8);
    private final JButton fetch = new JButton("Show tides");
    private final JButton previousDay = new JButton("◀");
    private final JButton nextDay = new JButton("▶");
    private final JLabel dayLabel = new JLabel(" ");
    private final JButton copy = new JButton("Copy");
    private final JButton save = new JButton("Save PNG…");
    private final JLabel status = new JLabel(" ");
    private final ChartPanel chart = new ChartPanel();

    private TideData data;
    private LocalDate day;
    private BufferedImage image;
    private SwingWorker<TideData, Void> worker;

    /** The place that was picked, and what the map data called it. */
    private TidePoint point;

    private TideWindow(Preferences preferences, RenderSpec renderSpec, TidePoint point) {
        super("Tide chart");
        this.preferences = preferences;
        this.renderSpec = renderSpec;
        this.client = new StormglassClient(preferences::stormglassApiKey);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setIconImages(MainWindow.appIcons());
        setPoint(point);
        setContentPane(buildContent());
        setPreferredSize(new Dimension(1000, 680));
        pack();
    }

    private void setPoint(TidePoint chosen) {
        this.point = chosen;
        latitude.setText(format(chosen.lat()));
        longitude.setText(format(chosen.lon()));
        setTitle(chosen.isNamed() ? "Tide chart — " + chosen.name() : "Tide chart");
    }

    private static String format(double degrees) {
        return String.format(Locale.ROOT, "%.4f", degrees);
    }

    // ---- layout -----------------------------------------------------------

    private JPanel buildContent() {
        final JPanel place = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        place.add(new JLabel("Latitude"));
        place.add(latitude);
        place.add(new JLabel("Longitude"));
        place.add(longitude);
        place.add(fetch);
        fetch.addActionListener(e -> load());

        final JPanel days = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        days.add(previousDay);
        dayLabel.setPreferredSize(new Dimension(130, dayLabel.getPreferredSize().height));
        dayLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        days.add(dayLabel);
        days.add(nextDay);
        previousDay.addActionListener(e -> stepDay(-1));
        nextDay.addActionListener(e -> stepDay(1));

        final JPanel top = new JPanel(new BorderLayout());
        top.add(place, BorderLayout.WEST);
        top.add(days, BorderLayout.EAST);

        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        actions.add(copy);
        actions.add(save);
        copy.addActionListener(e -> copyChart());
        save.addActionListener(e -> saveChart());

        status.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        final JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(actions, BorderLayout.EAST);

        final JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(chart, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        updateControls();
        return content;
    }

    /** Letterboxes the rendered chart, which has its own size and shape. */
    private final class ChartPanel extends JPanel {

        ChartPanel() {
            setPreferredSize(new Dimension(960, 540));
            // The chart is a light document whatever the window is doing, so
            // the surround is the only part of this panel that follows a theme.
            setOpaque(true);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            final java.awt.Color surround = javax.swing.UIManager.getColor("Panel.background");
            setBackground(surround != null ? surround : new java.awt.Color(236, 240, 244));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image == null) return;
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                   RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                final double scale = Math.min((double) getWidth() / image.getWidth(),
                                              (double) getHeight() / image.getHeight());
                final int w = (int) Math.round(image.getWidth() * scale);
                final int h = (int) Math.round(image.getHeight() * scale);
                g.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            }
            finally {
                g.dispose();
            }
        }
    }

    // ---- fetching ----------------------------------------------------------

    /**
     * Fetches the ten days for whatever is in the coordinate fields.
     *
     * <p>The missing-key case is caught here rather than left to the request,
     * because "403 Forbidden" is a poor way to learn that a field in another
     * window is empty. The offer to open that window is the actual next
     * step.</p>
     */
    private void load() {
        if (worker != null && !worker.isDone()) return;

        final double[] point = readPoint();
        if (point == null) return;

        if (!client.hasApiKey()) {
            offerToSetTheKey();
            return;
        }

        setBusy(true);
        setStatus("Asking " + client.description() + "…");
        worker = new SwingWorker<>() {
            @Override
            protected TideData doInBackground() throws Exception {
                return client.fetchForecast(point[0], point[1]);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    show(get());
                }
                catch (java.util.concurrent.CancellationException e) {
                    setStatus("Cancelled");
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                catch (java.util.concurrent.ExecutionException e) {
                    failed(e.getCause());
                }
            }
        };
        worker.execute();
    }

    private void failed(Throwable cause) {
        LOG.log(Level.WARNING, "Tide data unavailable", cause);
        final String message = cause == null ? "unknown error" : String.valueOf(cause.getMessage());
        setStatus(message);
        JOptionPane.showMessageDialog(this, message, "No tide data",
                                      JOptionPane.WARNING_MESSAGE);
    }

    private void offerToSetTheKey() {
        final int choice = JOptionPane.showConfirmDialog(this,
                "Tide charts come from Storm Glass, which needs an API key.\n\n"
                + "Open Preferences to enter one?",
                "No Storm Glass API key", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.INFORMATION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        if (PreferencesDialog.show(this, preferences, renderSpec) && client.hasApiKey()) {
            load();
        }
    }

    /** @return {@code {lat, lon}}, or null with the reason already on screen */
    private double[] readPoint() {
        try {
            final double lat = Double.parseDouble(latitude.getText().trim());
            final double lon = Double.parseDouble(longitude.getText().trim());
            if (lat < -90 || lat > 90) {
                setStatus("Latitude runs from -90 to 90");
                return null;
            }
            return new double[]{lat, BoundingBox.normaliseLon(lon)};
        }
        catch (NumberFormatException e) {
            setStatus("Enter a latitude and a longitude in degrees");
            return null;
        }
    }

    // ---- showing -----------------------------------------------------------

    private void show(TideData fetched) {
        this.data = fetched;
        final ZoneId zone = renderSpec.zone();
        final List<LocalDate> days = fetched.daysCovered(zone);
        if (days.isEmpty()) {
            setStatus("Storm Glass returned no tide data for this point");
            this.day = null;
            this.image = null;
            chart.repaint();
            updateControls();
            return;
        }
        final LocalDate today = LocalDate.now(zone);
        this.day = days.contains(today) ? today : days.get(0);
        draw();
    }

    private void stepDay(int by) {
        if (data == null || day == null) return;
        final List<LocalDate> days = data.daysCovered(renderSpec.zone());
        final int at = days.indexOf(day) + by;
        if (at < 0 || at >= days.size()) return;
        this.day = days.get(at);
        draw();
    }

    private void draw() {
        if (data == null || day == null) return;
        final int[] size = TideChart.fitSize(renderSpec.maxWidth(), renderSpec.maxHeight());
        image = TideChart.render(data, day, renderSpec.zone(), heading(), size[0], size[1]);
        chart.repaint();
        dayLabel.setText(DAY_LABEL.format(day));
        setStatus(describe());
        updateControls();
    }

    /**
     * The point the chart is headed with.
     *
     * <p>The picked name is kept only while the fields still hold the numbers
     * it was given for. Typing a new latitude moves the chart somewhere else,
     * and leaving "Vũng Tàu" over it would be a caption that has stopped being
     * true.</p>
     */
    private TidePoint heading() {
        final double[] at = readPoint();
        if (at == null) return point;
        final boolean moved = point == null
                || Math.abs(at[0] - point.lat()) > 1e-6
                || Math.abs(at[1] - point.lon()) > 1e-6;
        return moved ? new TidePoint(null, at[0], at[1]) : point;
    }

    private String describe() {
        final StringBuilder said = new StringBuilder();
        if (data.station() != null && data.station().name() != null) {
            said.append(data.station().describe());
        }
        if (data.cached()) {
            said.append(said.length() > 0 ? "  ·  " : "");
            said.append("cached at ")
                .append(RETRIEVED.format(data.retrievedAt().atZone(renderSpec.zone())))
                .append(" - no request made");
        }
        else if (data.quota().isKnown()) {
            said.append(said.length() > 0 ? "  ·  " : "").append(data.quota().describe());
        }
        return said.length() == 0 ? " " : said.toString();
    }

    private void updateControls() {
        final boolean haveChart = image != null;
        copy.setEnabled(haveChart);
        save.setEnabled(haveChart);

        final List<LocalDate> days = (data == null)
                ? List.of() : data.daysCovered(renderSpec.zone());
        final int at = (day == null) ? -1 : days.indexOf(day);
        previousDay.setEnabled(at > 0);
        nextDay.setEnabled(at >= 0 && at < days.size() - 1);
        if (day == null) dayLabel.setText(" ");
    }

    private void setBusy(boolean busy) {
        fetch.setEnabled(!busy);
        setCursor(java.awt.Cursor.getPredefinedCursor(
                busy ? java.awt.Cursor.WAIT_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
    }

    private void setStatus(String message) {
        status.setText(message == null || message.isBlank() ? " " : message);
    }

    // ---- taking the chart away ----------------------------------------------

    private void copyChart() {
        if (image == null) return;
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new ImageTransferable(image), null);
            setStatus("Tide chart copied to the clipboard ("
                    + image.getWidth() + "×" + image.getHeight() + ")");
        }
        catch (IllegalStateException e) {
            LOG.log(Level.WARNING, "Clipboard unavailable", e);
            setStatus("The clipboard is busy - try again");
        }
    }

    private void saveChart() {
        if (image == null || day == null) return;
        final Path target = preferences.outputDir().resolve("tide-" + day + ".png");
        try {
            PngWriter.write(image, target);
            setStatus("Written to " + target);
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write " + target, e);
            setStatus("Could not write " + target + ": " + e.getMessage());
        }
    }

    // ---- opening -------------------------------------------------------------

    /**
     * Shows the tide for a chosen point, reusing the window if it is open.
     *
     * <p>Fetches straight away rather than waiting for a button. By the time
     * this is called the user has scrolled to a place, clicked it and
     * confirmed what it was - asking a fourth time would be asking them to
     * repeat themselves.</p>
     */
    public static void show(Window owner, Preferences preferences,
                            RenderSpec renderSpec, TidePoint point) {
        TideWindow window = open;
        if (window != null && window.isDisplayable()) {
            window.setPoint(point);
            window.toFront();
            window.requestFocus();
        }
        else {
            window = new TideWindow(preferences, renderSpec, point);
            open = window;
            window.setLocationRelativeTo(owner);
            window.setVisible(true);
        }
        final TideWindow showing = window;
        SwingUtilities.invokeLater(showing::load);
    }
}
