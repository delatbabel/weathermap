package org.weathermap.gui;

import org.weathermap.model.Theme;

import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Window;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Applies a {@link Theme}. The only class that knows FlatLaf exists.
 *
 * <p>Kept to one place so the look and feel is a dependency of the window
 * rather than of the application: {@link org.weathermap.cli.WeatherMapCli} runs
 * without ever loading a Swing class, and would keep running if this file were
 * deleted.</p>
 *
 * <h2>Why a look and feel rather than styling the components</h2>
 *
 * <p>The default Metal look is the reason the application looked home-made -
 * not any one control, but the whole of it: the bevels, the fonts, the spacing.
 * That is exactly what a look and feel replaces wholesale, so a theme is about
 * fifteen lines rather than a pass over every panel. FlatLaf also gives the two
 * themes the same metrics, which is what lets the switch happen live: only
 * colours change, so no window has to be rebuilt or even resized.</p>
 *
 * <h2>Failing soft</h2>
 *
 * <p>A missing FlatLaf is a {@link NoClassDefFoundError}, not an exception, and
 * it is thrown at first use rather than at startup. That happens for real: the
 * plain jar has no dependencies inside it, so anyone running
 * {@code java -jar weathermap.jar} without the assembly gets one. An
 * unthemed window is a worse-looking application; a window that will not open
 * is a broken one. So the error is caught and the platform look and feel used
 * instead.</p>
 */
public final class Themes {

    private static final Logger LOG = Logger.getLogger(Themes.class.getName());

    private static final String LIGHT_CLASS = "com.formdev.flatlaf.themes.FlatMacLightLaf";
    private static final String DARK_CLASS = "com.formdev.flatlaf.themes.FlatMacDarkLaf";

    private static Theme current = Theme.LIGHT;

    private Themes() { }

    /** The theme in force. */
    public static Theme current() { return current; }

    /**
     * Installs a theme, before any window is created.
     *
     * @return true if the theme was applied; false if the platform look and feel
     *         is in use instead
     */
    public static boolean install(Theme theme) {
        current = theme;
        final String className = (theme == Theme.DARK) ? DARK_CLASS : LIGHT_CLASS;
        try {
            // Rounded corners on the controls, and a window title bar drawn by
            // the look and feel rather than by the desktop, so the dark theme is
            // dark all the way to the edge instead of stopping at a grey frame.
            UIManager.put("Component.arc", 8);
            UIManager.put("Button.arc", 8);
            UIManager.put("ProgressBar.arc", 8);
            UIManager.put("TextComponent.arc", 6);
            UIManager.put("ScrollBar.showButtons", false);
            UIManager.put("TitlePane.unifiedBackground", true);
            System.setProperty("flatlaf.useWindowDecorations", "true");
            System.setProperty("flatlaf.menuBarEmbedded", "true");

            UIManager.setLookAndFeel(className);
            return true;
        }
        catch (NoClassDefFoundError | ClassNotFoundException e) {
            LOG.info("FlatLaf is not on the classpath, so the platform look and "
                    + "feel is used. Run the jar-with-dependencies for the themes.");
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Could not install the " + theme.id() + " theme", e);
        }
        return installFallback();
    }

    /**
     * Switches theme on a running application.
     *
     * <p>Every open window is rebuilt, including dialogs, because a file chooser
     * left over from the old theme is the one thing a user will notice.</p>
     */
    public static void apply(Theme theme) {
        if (!install(theme)) return;
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
            if (window instanceof JDialog || window.isVisible()) {
                // Layout metrics are identical between the two themes, so this
                // only settles borders that cache their insets.
                window.validate();
            }
        }
    }

    private static boolean installFallback() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "Platform look and feel unavailable too", e);
        }
        return false;
    }
}
