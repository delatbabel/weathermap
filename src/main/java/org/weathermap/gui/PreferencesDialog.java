package org.weathermap.gui;

import org.weathermap.model.Preferences;
import org.weathermap.model.RenderSpec;
import org.weathermap.model.Theme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One place for the settings that are not part of a chart.
 *
 * <p>The API key, the time zone, the output size and the theme have nothing in
 * common except that they are all decisions about the application rather than
 * about a particular map, and before this they were in three different places:
 * the zone behind <b>Chart</b>, the theme behind <b>Appearance</b>, the size
 * nowhere at all, and the key not yet anywhere.</p>
 *
 * <p><b>The Appearance menu stays.</b> Changing theme is a thing people do on a
 * whim when the light in the room changes, and burying it two clicks deeper to
 * satisfy a tidier menu structure would be a worse application. The two are
 * kept in step instead - {@code MainWindow} re-selects the radio button after
 * this dialog applies - which is the small cost of having it in both places.</p>
 *
 * <p>Nothing is applied until <b>Save</b>: a dialog that changes the theme as
 * you scroll past it cannot be cancelled.</p>
 */
public final class PreferencesDialog extends JDialog {

    /** Where to get a key, shown under the field so the answer is to hand. */
    private static final String SIGN_UP = "dashboard.stormglass.io";

    private final Preferences preferences;
    private final RenderSpec renderSpec;

    private final JPasswordField apiKey = new JPasswordField(28);
    private final JCheckBox showKey = new JCheckBox("Show");
    private final JComboBox<String> zone = new JComboBox<>();
    private final JSpinner width;
    private final JSpinner height;
    private final JComboBox<Theme> theme = new JComboBox<>(Theme.values());
    private final JSpinner cacheBudget;

    private boolean saved;

    private PreferencesDialog(Window owner, Preferences preferences, RenderSpec renderSpec) {
        super(owner, "Preferences", Dialog.ModalityType.APPLICATION_MODAL);
        this.preferences = preferences;
        this.renderSpec = renderSpec;

        // Generous ceilings rather than none: a typo of an extra digit asks for
        // an image of a hundred million pixels and the window stops responding
        // while it is composited.
        this.width = pixelSpinner(renderSpec.maxWidth());
        this.height = pixelSpinner(renderSpec.maxHeight());
        this.cacheBudget = new JSpinner(new SpinnerNumberModel(
                preferences.cacheBudgetMegabytes(), 0, 200_000, 256));
        this.cacheBudget.setEditor(new JSpinner.NumberEditor(this.cacheBudget, "#"));

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(buildContent());
        populate();
        pack();
        setResizable(false);
    }

    // ---- layout ----------------------------------------------------------

    private JPanel buildContent() {
        final JPanel fields = new JPanel(new GridBagLayout());
        fields.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));

        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;

        row = addSection(fields, c, row, "Tides");
        c.gridx = 0; c.gridy = row;
        fields.add(new JLabel("Storm Glass API key"), c);
        final JPanel keyRow = new JPanel(new BorderLayout(6, 0));
        keyRow.add(apiKey, BorderLayout.CENTER);
        keyRow.add(showKey, BorderLayout.EAST);
        c.gridx = 1; c.gridy = row++;
        fields.add(keyRow, c);

        c.gridx = 1; c.gridy = row++;
        fields.add(hint("Tide charts need a key. Free keys come from " + SIGN_UP + "."), c);

        row = addSection(fields, c, row, "Charts");
        c.gridx = 0; c.gridy = row;
        fields.add(new JLabel("Time zone"), c);
        c.gridx = 1; c.gridy = row++;
        fields.add(zone, c);

        c.gridx = 1; c.gridy = row++;
        fields.add(hint("Times on every chart are written in this zone. "
                + "This machine is set to " + ZoneId.systemDefault().getId() + "."), c);

        c.gridx = 0; c.gridy = row;
        fields.add(new JLabel("Output size"), c);
        final JPanel sizeRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        sizeRow.add(width);
        sizeRow.add(new JLabel("×"));
        sizeRow.add(height);
        sizeRow.add(new JLabel("pixels"));
        c.gridx = 1; c.gridy = row++;
        fields.add(sizeRow, c);

        c.gridx = 1; c.gridy = row++;
        fields.add(hint("The largest a written chart may be; "
                + "the area's own shape decides the rest."), c);

        row = addSection(fields, c, row, "Downloads");
        c.gridx = 0; c.gridy = row;
        fields.add(new JLabel("Cache limit"), c);
        final JPanel cacheRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        cacheRow.add(cacheBudget);
        cacheRow.add(new JLabel("MB"));
        c.gridx = 1; c.gridy = row++;
        fields.add(cacheRow, c);

        c.gridx = 1; c.gridy = row++;
        fields.add(hint(cacheHint()), c);

        row = addSection(fields, c, row, "Appearance");
        c.gridx = 0; c.gridy = row;
        fields.add(new JLabel("Theme"), c);
        c.gridx = 1; c.gridy = row++;
        fields.add(theme, c);

        c.gridx = 1; c.gridy = row;
        fields.add(hint("The window only. A chart is a document and keeps its own colours."), c);

        final JButton save = new JButton("Save");
        save.addActionListener(e -> apply());
        final JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());

        final JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(save);
        getRootPane().setDefaultButton(save);

        final JPanel content = new JPanel(new BorderLayout());
        content.add(fields, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        return content;
    }

    private int addSection(JPanel panel, GridBagConstraints c, int row, String title) {
        final JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        final int savedTop = c.insets.top;
        c.insets = new Insets(row == 0 ? 0 : 14, 4, 4, 4);
        c.gridx = 0; c.gridy = row; c.gridwidth = 2;
        panel.add(label, c);
        c.gridwidth = 1;
        c.insets = new Insets(savedTop, 4, 4, 4);
        return row + 1;
    }

    /**
     * What the cache holds now, so the number above it means something.
     *
     * <p>Measured rather than guessed at: walking a thousand files takes
     * single-digit milliseconds, and "the oldest go when it passes 2048 MB"
     * reads very differently when the line underneath says it is already at
     * 943.</p>
     */
    private static String cacheHint() {
        final long bytes = new org.weathermap.util.Cache().size();
        return String.format(java.util.Locale.ROOT,
                "Downloads are kept in ~/.weathermap/cache, now %.0f MB. "
                + "The oldest go when it passes this. 0 means no limit.",
                bytes / 1e6);
    }

    /**
     * A spinner for a pixel count.
     *
     * <p>Without the format it groups the thousands and offers "1,600", which
     * is a number of things rather than a size and is not what anyone would
     * type back in.</p>
     */
    private static JSpinner pixelSpinner(int value) {
        final JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, 64, 8000, 100));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
        return spinner;
    }

    /**
     * Small, muted explanatory text.
     *
     * <p>Coloured rather than disabled. A disabled label is the right grey by
     * accident and the wrong thing by intent: it says the setting above it
     * cannot be used, and it takes the text out of reach of a screen
     * reader.</p>
     */
    private static JLabel hint(String text) {
        final JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, label.getFont().getSize2D() - 1f));
        final java.awt.Color muted = javax.swing.UIManager.getColor("Label.disabledForeground");
        label.setForeground(muted != null ? muted : new java.awt.Color(120, 120, 120));
        return label;
    }

    // ---- values -----------------------------------------------------------

    private void populate() {
        apiKey.setText(preferences.stormglassApiKey());
        // Masked by default and revealed on request. The file it is written to
        // is plain text, so this is only about the person behind you - but that
        // is who a key on a screen in an office is actually exposed to.
        final char bullet = apiKey.getEchoChar();
        showKey.addActionListener(e -> apiKey.setEchoChar(showKey.isSelected() ? 0 : bullet));

        final List<String> ids = new ArrayList<>(ZoneId.getAvailableZoneIds());
        Collections.sort(ids);
        for (String id : ids) zone.addItem(id);
        zone.setEditable(true);
        zone.setSelectedItem(renderSpec.zone().getId());

        theme.setSelectedItem(Themes.current());
        theme.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof Theme t) setText(t.displayName());
                return this;
            }
        });
    }

    /** Validates, stores and closes. Leaves the dialog open on a bad value. */
    private void apply() {
        final ZoneId chosenZone;
        final String id = String.valueOf(zone.getSelectedItem()).trim();
        try {
            chosenZone = ZoneId.of(id);
        }
        catch (DateTimeException e) {
            JOptionPane.showMessageDialog(this,
                    "\"" + id + "\" is not a time zone this machine knows.\n\n"
                    + "Use an IANA identifier such as Asia/Bangkok, Europe/London or UTC.",
                    "Unknown time zone", JOptionPane.WARNING_MESSAGE);
            return;
        }

        renderSpec.setZone(chosenZone);
        renderSpec.setMaxSize((Integer) width.getValue(), (Integer) height.getValue());
        preferences.setRenderSpec(renderSpec);
        preferences.setStormglassApiKey(new String(apiKey.getPassword()));
        preferences.setCacheBudgetMegabytes((Integer) cacheBudget.getValue());

        final Theme chosenTheme = (Theme) theme.getSelectedItem();
        if (chosenTheme != null && chosenTheme != Themes.current()) {
            Themes.apply(chosenTheme);
            preferences.setTheme(chosenTheme);
        }
        preferences.save();
        saved = true;
        dispose();
    }

    /**
     * Shows the dialog.
     *
     * @return true when something was saved, so the caller can re-read what it
     *         is showing
     */
    public static boolean show(Window owner, Preferences preferences, RenderSpec renderSpec) {
        final PreferencesDialog dialog = new PreferencesDialog(owner, preferences, renderSpec);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return dialog.saved;
    }
}
