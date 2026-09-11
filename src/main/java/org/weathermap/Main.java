package org.weathermap;

import org.weathermap.cli.WeatherMapCli;
import org.weathermap.gui.MainWindow;

import java.awt.GraphicsEnvironment;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for both front ends.
 *
 * <p>One jar, two behaviours: with {@code --cli} (or any arguments at all, or no
 * display) it runs the command-line tool; otherwise it opens the window. A
 * single artefact means the desktop application and the cron job can never drift
 * apart in version, and the shared {@link MapService} means they cannot drift
 * apart in behaviour.</p>
 */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        configureLogging(args);

        if (wantsCli(args)) {
            System.exit(WeatherMapCli.run(args));
        }
        MainWindow.launch();
    }

    /**
     * The GUI is the default, but only when it can actually open. A headless JVM
     * asked to show a window throws {@link java.awt.HeadlessException} out of
     * {@code main}, which is a poor way to learn there is no display.
     */
    private static boolean wantsCli(String[] args) {
        for (String a : args) {
            if ("--cli".equals(a)) return true;
        }
        if (args.length > 0) return true;
        return GraphicsEnvironment.isHeadless();
    }

    private static void configureLogging(String[] args) {
        boolean quiet = false;
        for (String a : args) {
            if ("--quiet".equals(a) || "-q".equals(a)) quiet = true;
        }
        final Logger root = Logger.getLogger("");
        for (var handler : root.getHandlers()) root.removeHandler(handler);
        final ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(quiet ? Level.WARNING : Level.INFO);
        root.addHandler(handler);
        root.setLevel(quiet ? Level.WARNING : Level.INFO);
    }
}
