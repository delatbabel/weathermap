package org.weathermap.gui;

import org.weathermap.model.GribCatalog;
import org.weathermap.model.GribLevel;
import org.weathermap.model.GribModel;
import org.weathermap.model.GribSelection;
import org.weathermap.model.GribVariable;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses the model, the run, the forecast hours and the variable/level pairs.
 *
 * <p>Forecast hours are typed as a comma-and-range list ({@code 0,6,12-24}) rather
 * than picked from a list, because the useful selections are sequences and a
 * multi-select list of 129 GFS hours is unusable.</p>
 *
 * <p><b>Availability is not validated here.</b> Whether a variable exists at a
 * level for a model is only knowable from that model's filter form, so an
 * impossible pair is accepted and fails at download time with an empty result.
 * See {@link GribCatalog}.</p>
 */
public final class DataPanel extends JPanel {

    private final JComboBox<GribModel> model = new JComboBox<>(GribModel.values());
    private final JList<GribVariable> variables =
            new JList<>(new DefaultListModel<>());
    private final JList<GribLevel> levels = new JList<>(new DefaultListModel<>());
    private final JTextField forecastHours = new JTextField("0,6,12", 12);
    private final JCheckBox latestRun = new JCheckBox("Use the latest published run", true);

    private final JCheckBox series = new JCheckBox("Series");
    private final javax.swing.JSpinner seriesStep = new javax.swing.JSpinner(
            new javax.swing.SpinnerNumberModel(GribSelection.DEFAULT_SERIES_STEP_HOURS, 1, 24, 1));
    private final javax.swing.JSpinner seriesBack = new javax.swing.JSpinner(
            new javax.swing.SpinnerNumberModel(0, 0, GribSelection.MAX_SERIES_HOURS_BACK, 6));
    private final javax.swing.JSpinner seriesSpan = new javax.swing.JSpinner(
            new javax.swing.SpinnerNumberModel(GribSelection.DEFAULT_SERIES_SPAN_HOURS, 0, 384, 3));
    private final JLabel note = new JLabel(" ");

    public DataPanel() {
        setBorder(BorderFactory.createTitledBorder("GRIB data"));
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        final DefaultListModel<GribVariable> vm =
                (DefaultListModel<GribVariable>) variables.getModel();
        GribCatalog.VARIABLES.forEach(vm::addElement);
        final DefaultListModel<GribLevel> lm = (DefaultListModel<GribLevel>) levels.getModel();
        GribCatalog.LEVELS.forEach(lm::addElement);

        variables.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        levels.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        add(labelled("Model", model));
        add(labelled("Forecast hours", forecastHours));
        add(seriesRow());
        add(latestRun);
        add(listPane("Variables", variables));
        add(listPane("Levels", levels));

        note.setFont(note.getFont().deriveFont(java.awt.Font.PLAIN, 10f));
        add(note);

        model.addActionListener(e -> updateNote());
        series.addActionListener(e -> updateSeriesEnabled());
        updateSeriesEnabled();
        updateNote();
    }

    /**
     * The series controls: a chart every so many hours, for so many hours ahead.
     *
     * <p>On one line with the checkbox that governs them, because three separate
     * rows would read as three independent settings when they are one
     * sentence.</p>
     */
    private JPanel seriesRow() {
        series.setToolTipText("A chart at each step across the window, instead of "
                + "the listed forecast hours");
        seriesBack.setToolTipText("Hours before now. Past charts come from the runs "
                + "of the time, so their lead is short - often zero, the model's "
                + "own analysis of that moment.");
        seriesSpan.setToolTipText("Hours after now, as far as the model reaches");
        final JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        // Reads as one sentence, in the order the charts run: "Series from 48 h
        // back to 48 h ahead, every 6 h". It said "Series from now" before,
        // which contradicted the control immediately to its right the moment
        // anyone asked for the past.
        row.add(series);
        row.add(javax.swing.Box.createHorizontalStrut(8));
        row.add(new JLabel("from"));
        row.add(javax.swing.Box.createHorizontalStrut(4));
        row.add(seriesBack);
        row.add(javax.swing.Box.createHorizontalStrut(4));
        row.add(new JLabel("h back to"));
        row.add(javax.swing.Box.createHorizontalStrut(4));
        row.add(seriesSpan);
        row.add(javax.swing.Box.createHorizontalStrut(4));
        row.add(new JLabel("h ahead, every"));
        row.add(javax.swing.Box.createHorizontalStrut(4));
        row.add(seriesStep);
        row.add(javax.swing.Box.createHorizontalStrut(4));
        row.add(new JLabel("h"));
        row.add(javax.swing.Box.createHorizontalGlue());
        return row;
    }

    /**
     * A series and a list of hours are two answers to the same question, so only
     * one of them is ever live.
     */
    private void updateSeriesEnabled() {
        final boolean on = series.isSelected();
        seriesStep.setEnabled(on);
        seriesBack.setEnabled(on);
        seriesSpan.setEnabled(on);
        forecastHours.setEnabled(!on);
        forecastHours.setToolTipText(on
                ? "Ignored while a series is set - the series decides the hours"
                : null);
    }

    private void updateNote() {
        final GribModel m = (GribModel) model.getSelectedItem();
        note.setText(m == null ? " "
                : String.format("%.2f° grid, out to f%03d", m.resolutionDegrees(), m.maxForecastHour()));
        if (m == null) return;

        // How far ahead a series may run is the model's reach, not a constant.
        // HRRR stops at f048; offering 384 would let someone ask for charts that
        // can only fail at download time.
        final javax.swing.SpinnerNumberModel ahead =
                (javax.swing.SpinnerNumberModel) seriesSpan.getModel();
        ahead.setMaximum(m.maxForecastHour());
        if ((Integer) ahead.getValue() > m.maxForecastHour()) {
            ahead.setValue(m.maxForecastHour());
        }
    }

    private static JPanel labelled(String text, javax.swing.JComponent field) {
        final JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(new JLabel(text), BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        return p;
    }

    private static JPanel listPane(String title, JList<?> list) {
        final JPanel p = new JPanel(new BorderLayout());
        p.add(new JLabel(title), BorderLayout.NORTH);
        final JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(220, 110));
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    /** Fills the controls from a stored selection. */
    public void setSelection(GribSelection selection) {
        model.setSelectedItem(selection.model());
        forecastHours.setText(compact(selection.forecastHours()));
        series.setSelected(selection.hasSeries());
        if (selection.hasSeries()) seriesStep.setValue(selection.seriesStepHours());
        seriesBack.setValue(selection.seriesHoursBack());
        seriesSpan.setValue(selection.seriesSpanHours());
        updateSeriesEnabled();
        latestRun.setSelected(selection.followsLatestRun());
        selectIn(variables, selection.variables());
        selectIn(levels, selection.levels());
        updateNote();
    }

    /**
     * Reads the controls back.
     *
     * @return the selection, or {@code null} if the forecast hours do not parse
     *         or nothing is selected (the reason is shown in the panel)
     */
    public GribSelection selection() {
        final GribSelection sel = new GribSelection();
        sel.setModel((GribModel) model.getSelectedItem());

        List<Integer> hours = List.of(0);
        if (!series.isSelected()) {
            try {
                hours = parseHours(forecastHours.getText());
            }
            catch (IllegalArgumentException e) {
                note.setText(e.getMessage());
                return null;
            }
        }
        final Set<GribVariable> vars = new LinkedHashSet<>(variables.getSelectedValuesList());
        final Set<GribLevel> levs = new LinkedHashSet<>(levels.getSelectedValuesList());
        if (vars.isEmpty() || levs.isEmpty()) {
            note.setText("Select at least one variable and one level");
            return null;
        }
        try {
            sel.setForecastHours(hours);
            sel.setVariables(vars);
            sel.setLevels(levs);
        }
        catch (IllegalArgumentException e) {
            note.setText(e.getMessage());
            return null;
        }
        if (series.isSelected()) {
            try {
                sel.setSeries((Integer) seriesStep.getValue(),
                              (Integer) seriesBack.getValue(),
                              (Integer) seriesSpan.getValue());
            }
            catch (IllegalArgumentException e) {
                note.setText(e.getMessage());
                return null;
            }
        }
        if (latestRun.isSelected()) sel.useLatestRun();
        updateNote();
        return sel;
    }

    /** Parses {@code 0,6,12-24} into a sorted, de-duplicated list. */
    static List<Integer> parseHours(String text) {
        final Set<Integer> out = new java.util.TreeSet<>();
        for (String part : text.split(",")) {
            final String t = part.trim();
            if (t.isEmpty()) continue;
            final int dash = t.indexOf('-');
            try {
                if (dash > 0) {
                    final int from = Integer.parseInt(t.substring(0, dash).trim());
                    final int to = Integer.parseInt(t.substring(dash + 1).trim());
                    if (to < from) throw new IllegalArgumentException("range " + t + " runs backwards");
                    for (int h = from; h <= to; h++) out.add(h);
                }
                else {
                    out.add(Integer.parseInt(t));
                }
            }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("cannot read forecast hour '" + t + "'");
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Enter at least one forecast hour");
        return new ArrayList<>(out);
    }

    /** The inverse of {@link #parseHours}, collapsing runs back into ranges. */
    static String compact(List<Integer> hours) {
        if (hours.isEmpty()) return "";
        final StringBuilder out = new StringBuilder();
        int runStart = hours.get(0);
        int previous = runStart;
        for (int i = 1; i <= hours.size(); i++) {
            final int current = (i < hours.size()) ? hours.get(i) : Integer.MIN_VALUE;
            if (current != previous + 1) {
                if (out.length() > 0) out.append(',');
                out.append(runStart == previous ? String.valueOf(runStart)
                                                : runStart + "-" + previous);
                runStart = current;
            }
            previous = current;
        }
        return out.toString();
    }

    private static <T> void selectIn(JList<T> list, Set<T> wanted) {
        final List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < list.getModel().getSize(); i++) {
            if (wanted.contains(list.getModel().getElementAt(i))) indices.add(i);
        }
        list.setSelectedIndices(indices.stream().mapToInt(Integer::intValue).toArray());
    }
}
