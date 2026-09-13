package org.weathermap.gui;

import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The User's Guide and the Developer's Guide, each a tab per page.
 *
 * <h2>Where the text comes from</h2>
 *
 * <p>The same Markdown that {@code docs/} holds, bundled into the jar and
 * rendered by {@link Markdown}. Writing a second copy for the application would
 * have produced two documents describing one program, and the one nobody was
 * looking at would have gone stale - which is the failure this project has
 * already had once, in a README that grew to nine subjects because it was the
 * only place anything was written down.</p>
 *
 * <h2>Links</h2>
 *
 * <p>A link between bundled pages switches tab rather than navigating, so the
 * tab strip never disagrees with what is on screen. A link to a page that is not
 * a tab is loaded in place; an external link opens in the desktop browser, since
 * {@code JEditorPane} is not a web browser and pretending otherwise gives
 * someone a broken page inside a help window.</p>
 */
public final class HelpWindow extends JFrame {

    private static final Logger LOG = Logger.getLogger(HelpWindow.class.getName());

    /**
     * One tab: what it is called, which bundled page it shows, and whether that
     * page is Markdown or plain text.
     *
     * <p>The licence is the reason for the distinction. It is a legal document
     * whose line breaks and indentation are part of it, and running it through
     * a Markdown renderer would reflow its paragraphs, turn its numbered
     * sections into a list and swallow the underscores in its addresses. It is
     * shown exactly as it ships.</p>
     */
    public record Page(String title, String resource, boolean plainText) {
        public Page(String title, String resource) { this(title, resource, false); }
    }

    public static final List<Page> USER_GUIDE = List.of(
            new Page("Basic Usage", "/help/basic-usage.md"),
            new Page("Series and Profiles", "/help/series-and-profiles.md"),
            new Page("Themes", "/help/themes.md"),
            new Page("Build and Run", "/help/build-and-run.md"),
            new Page("Instagram", "/help/instagram.md"),
            new Page("Image Hosting", "/help/image-hosting.md"),
            new Page("License", "/help/LICENSE", true));

    public static final List<Page> DEVELOPER_GUIDE = List.of(
            new Page("How It Works", "/help/how-it-works.md"),
            new Page("Rendering", "/help/rendering.md"),
            new Page("GRIB Decoding", "/help/grib-decoding.md"),
            new Page("Operations", "/help/operations.md"),
            new Page("Architecture", "/help/architecture/README.md"),
            new Page("Diagrams", "/help/architecture/diagrams.md"),
            new Page("Packaging", "/help/packaging.md"),
            new Page("Not Done Yet", "/help/not-done-yet.md"),
            new Page("License", "/help/LICENSE", true));

    /** One window per guide, reused so a second F1 raises the first window. */
    private static final Map<String, HelpWindow> OPEN = new LinkedHashMap<>();

    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<String, Integer> tabByResource = new LinkedHashMap<>();

    private HelpWindow(String title, List<Page> pages) {
        super(title);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        for (Page page : pages) {
            final JEditorPane pane = new JEditorPane();
            pane.setEditable(false);
            pane.setContentType("text/html");
            pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            pane.addHyperlinkListener(this::follow);
            pane.setText(render(page));
            pane.setCaretPosition(0);

            final JScrollPane scroll = new JScrollPane(pane);
            scroll.getVerticalScrollBar().setUnitIncrement(18);
            tabByResource.put(page.resource(), tabs.getTabCount());
            tabs.addTab(page.title(), scroll);
        }

        final javax.swing.JPanel root = new javax.swing.JPanel(new BorderLayout());
        root.add(tabs, BorderLayout.CENTER);
        setContentPane(root);
        setPreferredSize(new Dimension(940, 720));
        pack();
    }

    /**
     * Shows a guide, raising it if it is already open.
     *
     * @param owner the window to centre on
     */
    public static void show(Window owner, String title, List<Page> pages) {
        final HelpWindow existing = OPEN.get(title);
        if (existing != null && existing.isDisplayable()) {
            existing.toFront();
            existing.requestFocus();
            return;
        }
        final HelpWindow window = new HelpWindow(title, pages);
        OPEN.put(title, window);
        window.setLocationRelativeTo(owner);
        window.setVisible(true);
    }

    private String render(Page page) {
        final String resource = page.resource();
        try (InputStream in = HelpWindow.class.getResourceAsStream(resource)) {
            if (in == null) {
                return Markdown.toHtml("# Not bundled\n\nThe page `" + resource
                        + "` is missing from this build.\n", null);
            }
            final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (page.plainText()) return asPlainText(text);
            final String markdown = text;
            // Images resolve against the jar, so a diagram referenced as
            // ../diagrams/x.png finds the copy packaged beside the pages.
            final java.net.URL base = HelpWindow.class.getResource(resource);
            return Markdown.toHtml(markdown, base == null ? null : base.toString());
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read " + resource, e);
            return Markdown.toHtml("# Unreadable\n\n" + e.getMessage() + "\n", null);
        }
    }

    /**
     * A document shown exactly as written.
     *
     * <p>Wrapped in {@code <pre>} with its markup characters escaped, so the
     * ampersands and angle brackets in a licence stay visible rather than being
     * read as tags.</p>
     */
    private static String asPlainText(String text) {
        return "<html><head><style>"
                + "body { margin: 12px 16px; }"
                + "pre  { font-family: monospace; font-size: 9pt; }"
                + "</style></head><body><pre>"
                + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                + "</pre></body></html>";
    }

    private void follow(HyperlinkEvent event) {
        if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;

        final String target = String.valueOf(event.getDescription());
        if (target.startsWith("http://") || target.startsWith("https://")
                || target.startsWith("mailto:")) {
            openExternally(target);
            return;
        }

        // A relative link between documentation pages: find the tab showing it.
        final String page = target.startsWith("/") ? target
                : "/help/" + target.replaceFirst("^\\./", "").replaceFirst("^\\.\\./", "");
        final Integer tab = tabByResource.get(page);
        if (tab != null) {
            tabs.setSelectedIndex(tab);
            return;
        }
        final Integer nested = tabByResource.get("/help/architecture/" + target);
        if (nested != null) {
            tabs.setSelectedIndex(nested);
            return;
        }
        LOG.fine(() -> "No tab shows " + target + "; leaving the page where it is");
    }

    private void openExternally(String target) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(target));
            }
        }
        catch (Exception e) {
            // A missing browser is not worth a dialog over a documentation link.
            LOG.log(Level.FINE, "Could not open " + target, e);
        }
    }

    /** Convenience for the menu: the User's Guide. */
    public static void showUserGuide(Window owner) {
        SwingUtilities.invokeLater(() -> show(owner, "Weather Map — User's Guide", USER_GUIDE));
    }

    /** Convenience for the menu: the Developer's Guide. */
    public static void showDeveloperGuide(Window owner) {
        SwingUtilities.invokeLater(
                () -> show(owner, "Weather Map — Developer's Guide", DEVELOPER_GUIDE));
    }
}
