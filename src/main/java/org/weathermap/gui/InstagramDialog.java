package org.weathermap.gui;

import org.weathermap.instagram.InstagramClient;
import org.weathermap.model.InstagramAccount;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Asks for the Instagram account, and for how much of the series to post.
 *
 * <p>The fields are the ones the API actually needs, which is not what someone
 * expects: an account ID and an access token rather than a password, and the
 * public address of a directory, because Instagram fetches every image itself
 * and will not take an upload. The dialog says so rather than leaving a user to
 * wonder where the password box went, and links to the page that explains how
 * to obtain a token.</p>
 */
public final class InstagramDialog extends JDialog {

    /** What the user chose, when they chose to go ahead. */
    public record Request(InstagramAccount account, int chartCount, String caption) { }

    private static final String SETUP_URL =
            "https://developers.facebook.com/docs/instagram-platform/content-publishing";

    private final javax.swing.JComboBox<InstagramAccount.Login> login =
            new javax.swing.JComboBox<>(InstagramAccount.Login.values());
    private final JTextField profile = new JTextField(22);
    private final JTextField userId = new JTextField(22);
    private final JPasswordField token = new JPasswordField(22);
    private final JTextField publishDir = new JTextField(22);
    private final JTextField publicUrl = new JTextField(22);
    private final JSpinner chartCount;
    private final JTextArea caption = new JTextArea(4, 22);

    private Request result;

    private InstagramDialog(Window owner, InstagramAccount existing, int available) {
        super(owner, "Post to Instagram", ModalityType.APPLICATION_MODAL);

        // Never more than the carousel limit, nor more charts than there are.
        final int max = Math.max(2, Math.min(InstagramClient.MAX_CAROUSEL, available));
        chartCount = new JSpinner(new SpinnerNumberModel(Math.min(4, max), 2, max, 1));

        if (existing != null) {
            login.setSelectedItem(existing.login());
            profile.setText(existing.profile());
            userId.setText(existing.igUserId());
            token.setText(existing.accessToken());
            publishDir.setText(existing.publishDir() == null ? "" : existing.publishDir().toString());
            publicUrl.setText(existing.publicBaseUrl());
        }

        setContentPane(buildContent(available));
        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel buildContent(int available) {
        final JPanel fields = new JPanel(new GridBagLayout());
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;

        row = add(fields, c, row, "Token from", login,
                  "Instagram Login needs no Facebook Page and no login flow. "
                  + "The two are not interchangeable.");
        row = add(fields, c, row, "Profile", profile,
                  "The @handle. Shown here only; the API identifies the account by ID.");
        row = add(fields, c, row, "Instagram user ID", userId,
                  "The numeric ID of the professional account.");
        row = add(fields, c, row, "Access token", token,
                  "A long-lived token, or a Page token, which does not expire.");
        row = add(fields, c, row, "Publish folder", publishDir,
                  "Where the images are written for Instagram to fetch.");
        row = add(fields, c, row, "Public URL of that folder", publicUrl,
                  "Instagram fetches every image; nothing local can be posted.");
        row = add(fields, c, row, "Charts to post", chartCount,
                  "The chart on screen, then the next in the series. "
                  + available + " available from here.");

        c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.NORTHWEST;
        fields.add(new JLabel("Caption"), c);
        c.gridx = 1;
        caption.setLineWrap(true);
        caption.setWrapStyleWord(true);
        fields.add(new JScrollPane(caption), c);

        final JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        root.add(explanation(), BorderLayout.NORTH);
        root.add(fields, BorderLayout.CENTER);
        root.add(buttons(), BorderLayout.SOUTH);
        return root;
    }

    private JComponent explanation() {
        final JLabel note = new JLabel("<html><body style='width: 380px'>"
                + "<b>Instagram has no password-based posting.</b> Publishing goes "
                + "through Meta's Content Publishing API, which needs an access token "
                + "and a professional account. It also fetches every image from a "
                + "public URL rather than accepting an upload.<br><br>"
                + "For one account you own, <b>Instagram Login</b> with Standard Access "
                + "is enough: generate a token in the App Dashboard, with no login flow "
                + "to build and no Facebook Page required."
                + "</body></html>");
        note.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

        final JButton howTo = new JButton("How to get a token…");
        howTo.addActionListener(e -> browse(SETUP_URL));

        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(note);
        final JPanel row = new JPanel(new BorderLayout());
        row.add(howTo, BorderLayout.WEST);
        panel.add(row);
        return panel;
    }

    private JPanel buttons() {
        final JButton check = new JButton("Check settings");
        check.addActionListener(e -> checkSettings());
        final JButton post = new JButton("Post");
        final JButton cancel = new JButton("Cancel");

        post.addActionListener(e -> {
            final InstagramAccount account = read();
            if (!account.isComplete()) {
                JOptionPane.showMessageDialog(this,
                        "Fill in the account ID, the token, the publish folder and its "
                        + "public URL.\n\nThe folder and the URL must be the same place: "
                        + "Instagram fetches\nthe images rather than receiving them.",
                        "Not enough to post", JOptionPane.WARNING_MESSAGE);
                return;
            }
            result = new Request(account, (Integer) chartCount.getValue(), caption.getText());
            dispose();
        });
        cancel.addActionListener(e -> dispose());

        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.add(check);
        panel.add(Box.createHorizontalGlue());
        panel.add(cancel);
        panel.add(Box.createHorizontalStrut(6));
        panel.add(post);
        getRootPane().setDefaultButton(post);
        return panel;
    }

    /**
     * Confirms the folder and the URL are the same place, before a post.
     *
     * <p>A wrong pairing is the commonest mistake and the worst to diagnose: the
     * images write fine, the post fails inside Meta's fetch, and the error says
     * only that the media could not be retrieved. Writing one small file and
     * asking for it over HTTP settles it in a second.</p>
     */
    private void checkSettings() {
        final InstagramAccount account = read();
        if (!account.isComplete()) {
            JOptionPane.showMessageDialog(this, "Fill in every field first.",
                    "Nothing to check", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String problem = InstagramPublishCheck.run(account);
        if (problem == null) {
            JOptionPane.showMessageDialog(this,
                    "The publish folder is reachable at that URL.",
                    "Settings look right", JOptionPane.INFORMATION_MESSAGE);
        }
        else {
            JOptionPane.showMessageDialog(this, problem, "Settings problem",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private InstagramAccount read() {
        final String dir = publishDir.getText().trim();
        return new InstagramAccount(
                (InstagramAccount.Login) login.getSelectedItem(),
                profile.getText(), userId.getText(),
                new String(token.getPassword()),
                dir.isEmpty() ? null : Path.of(dir), publicUrl.getText());
    }

    private int add(JPanel panel, GridBagConstraints c, int row,
                    String label, JComponent field, String hint) {
        c.gridx = 0; c.gridy = row;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        panel.add(field, c);
        c.gridx = 1; c.gridy = row + 1;
        final JLabel note = new JLabel(hint);
        note.setFont(note.getFont().deriveFont(java.awt.Font.PLAIN, 10f));
        note.setForeground(new java.awt.Color(120, 120, 126));
        panel.add(note, c);
        return row + 2;
    }

    private void browse(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        }
        catch (Exception e) {
            JOptionPane.showMessageDialog(this, url, "Open this page",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * @param available how many charts follow the one on screen, inclusive
     * @return what to post, or empty if the user cancelled
     */
    public static Optional<Request> ask(Window owner, InstagramAccount existing, int available) {
        final InstagramDialog dialog = new InstagramDialog(owner, existing, available);
        dialog.setMinimumSize(new Dimension(520, 0));
        dialog.setVisible(true);
        return Optional.ofNullable(dialog.result);
    }
}
