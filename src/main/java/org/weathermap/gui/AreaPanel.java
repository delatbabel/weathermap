package org.weathermap.gui;

import org.weathermap.model.BoundingBox;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;

/**
 * Numeric entry for the selected rectangle, kept in step with the drag on
 * {@link MapPanel}.
 *
 * <p>Both routes exist because they answer different needs: dragging is how you
 * choose an area you can see, typing is how you reproduce one exactly - from a
 * previous run, a colleague, or a published bounding box.</p>
 */
public final class AreaPanel extends JPanel {

    private final JTextField north = new JTextField(8);
    private final JTextField south = new JTextField(8);
    private final JTextField west = new JTextField(8);
    private final JTextField east = new JTextField(8);
    private final JLabel status = new JLabel(" ");

    private Consumer<BoundingBox> listener;

    /** Guards against the panel echoing a change it was itself given. */
    private boolean updating;

    public AreaPanel() {
        setBorder(BorderFactory.createTitledBorder("Area"));
        setLayout(new GridBagLayout());

        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 1; c.gridy = 0;
        add(new JLabel("North"), c);
        c.gridx = 1; c.gridy = 1;
        add(north, c);

        c.gridx = 0; c.gridy = 2;
        add(new JLabel("West"), c);
        c.gridx = 0; c.gridy = 3;
        add(west, c);

        c.gridx = 2; c.gridy = 2;
        add(new JLabel("East"), c);
        c.gridx = 2; c.gridy = 3;
        add(east, c);

        c.gridx = 1; c.gridy = 4;
        add(new JLabel("South"), c);
        c.gridx = 1; c.gridy = 5;
        add(south, c);

        final JButton apply = new JButton("Apply");
        apply.addActionListener(e -> fireIfValid());
        c.gridx = 1; c.gridy = 6;
        add(apply, c);

        c.gridx = 0; c.gridy = 7; c.gridwidth = 3;
        status.setForeground(new java.awt.Color(160, 40, 40));
        add(status, c);
    }

    public void setListener(Consumer<BoundingBox> listener) {
        this.listener = listener;
    }

    /** Shows an area without firing the listener. */
    public void setArea(BoundingBox bbox) {
        updating = true;
        try {
            north.setText(String.valueOf(bbox.north()));
            south.setText(String.valueOf(bbox.south()));
            west.setText(String.valueOf(bbox.west()));
            east.setText(String.valueOf(bbox.east()));
            status.setText(" ");
        }
        finally {
            updating = false;
        }
    }

    /** @return the typed area, or {@code null} when the fields do not parse. */
    public BoundingBox area() {
        try {
            return BoundingBox.of(
                    Double.parseDouble(south.getText().trim()),
                    Double.parseDouble(west.getText().trim()),
                    Double.parseDouble(north.getText().trim()),
                    Double.parseDouble(east.getText().trim()));
        }
        catch (NumberFormatException e) {
            status.setText("Enter four numbers");
            return null;
        }
        catch (IllegalArgumentException e) {
            // BoundingBox explains exactly what is wrong - inverted corners, too
            // small, across the antimeridian - so show its message verbatim.
            status.setText(e.getMessage());
            return null;
        }
    }

    private void fireIfValid() {
        if (updating) return;
        final BoundingBox bbox = area();
        if (bbox != null && listener != null) {
            status.setText(" ");
            listener.accept(bbox);
        }
    }
}
