package org.weathermap.cli;

import org.weathermap.model.Preferences;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Where a run's output goes, which for an unattended one is not the terminal.
 *
 * <h2>Why {@code --quiet} was not quiet</h2>
 *
 * <p>It silenced the commentary and left the things it did not think of: the
 * name of every PNG written, the account posted to and the address of the post.
 * Behind those sat {@code java.util.logging}, whose default handler writes to
 * stderr - so a retry inside {@code Http} produced a WARNING on the console
 * from a job that had asked for silence. cron mails whatever a job prints, so
 * "quiet" that prints anything at all means a mail every night.</p>
 *
 * <p>Quiet now means nothing on stdout, and everything - the commentary, the
 * file names, the post, and the logging of every class that does any - goes to
 * a file instead.</p>
 *
 * <h2>Failures still reach stderr</h2>
 *
 * <p>Deliberately, and it is the one thing quiet does not swallow. A job that
 * fails silently every night is worse than one that says so: cron mails stderr,
 * which is how anybody finds out. Redirect it if that is genuinely not wanted -
 * the log file has the same lines either way.</p>
 */
public final class RunLog implements AutoCloseable {

    /** Kept small: this is a few dozen lines a night, not a service's log. */
    private static final long MAX_BYTES = 1024 * 1024;

    private final PrintStream out;
    private final Logger file;
    private final FileHandler handler;
    private final Path path;

    /** Console handlers taken off the root logger, to be put back on close. */
    private final java.util.List<Handler> displaced = new java.util.ArrayList<>();

    private RunLog(PrintStream out, Logger file, FileHandler handler, Path path) {
        this.out = out;
        this.file = file;
        this.handler = handler;
        this.path = path;
    }

    /** {@code ~/.weathermap/weathermap.log}. */
    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), Preferences.CONFIG_DIR, "weathermap.log");
    }

    /**
     * @param quiet    nothing on stdout, and a log file whether or not one was asked for
     * @param explicit a log file to use instead of the default; may be null
     */
    public static RunLog open(boolean quiet, Path explicit) {
        final Path target = explicit != null ? explicit : (quiet ? defaultFile() : null);
        if (target == null) {
            return new RunLog(System.out, null, null, null);
        }

        FileHandler handler = null;
        Logger logger = null;
        try {
            final Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);

            rotateIfLarge(target);
            // Limit 0 and one generation, so the live file is exactly the path
            // named and nothing else. FileHandler's own rotation appends ".0"
            // to the name it was given, which would make the file something
            // other than the one this class says it writes.
            handler = new FileHandler(target.toString(), 0, 1, true);
            handler.setFormatter(new OneLine());
            handler.setLevel(Level.ALL);

            // Onto the root logger, so every class that logs anything lands
            // here too rather than on the console.
            logger = Logger.getLogger("");
            logger.addHandler(handler);
            logger.setLevel(Level.INFO);
        }
        catch (IOException e) {
            // Losing the log is not a reason to lose the run. Say so once, on
            // stderr, which is the channel for things that went wrong.
            System.err.println("weathermap: could not open " + target + ": " + e.getMessage());
            return new RunLog(quiet ? null : System.out, null, null, null);
        }
        final RunLog log = new RunLog(quiet ? null : System.out, logger, handler, target);
        if (quiet) {
            // Taken off rather than silenced, and put back by close(): the root
            // logger is global, and a process that used this once should not be
            // left without console logging for everything it does afterwards.
            for (Handler existing : logger.getHandlers()) {
                if (existing != handler) {
                    log.displaced.add(existing);
                    logger.removeHandler(existing);
                }
            }
        }
        return log;
    }

    /** Normal output: the commentary, the files written, the post that was made. */
    public void say(String message) {
        if (out != null) out.println(message);
        if (file != null) file.info(message);
    }

    /** Something went wrong. Reaches stderr even when quiet. */
    public void problem(String message) {
        System.err.println(message);
        if (file != null) file.severe(message);
    }

    /** For anything wanting a plain {@code Consumer<String>} to report progress. */
    public java.util.function.Consumer<String> sink() { return this::say; }

    /** Where the log is being written, or null when it is not. */
    public Path file() { return path; }

    @Override
    public void close() {
        if (file != null) {
            for (Handler restored : displaced) file.addHandler(restored);
            displaced.clear();
            if (handler != null) file.removeHandler(handler);
        }
        if (handler != null) {
            handler.flush();
            handler.close();
        }
    }

    /**
     * Moves a log that has grown too big aside, keeping one previous file.
     *
     * <p>Unbounded is not an option for something a nightly job appends to
     * forever, and one generation is enough to still have last night's after
     * tonight's has rolled.</p>
     */
    private static void rotateIfLarge(Path target) throws IOException {
        if (!Files.isRegularFile(target) || Files.size(target) < MAX_BYTES) return;
        Files.move(target, target.resolveSibling(target.getFileName() + ".1"),
                   java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** One line per record: a log read by a person at 8am, not by a parser. */
    private static final class OneLine extends Formatter {
        private static final DateTimeFormatter STAMP =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        @Override
        public String format(LogRecord record) {
            final String when = STAMP.format(LocalDateTime.now());
            final String level = record.getLevel() == Level.INFO
                    ? "" : record.getLevel().getName() + " ";
            final StringBuilder out = new StringBuilder()
                    .append(when).append("  ").append(level)
                    .append(formatMessage(record)).append(System.lineSeparator());
            if (record.getThrown() != null) {
                out.append("    ").append(record.getThrown()).append(System.lineSeparator());
            }
            return out.toString();
        }
    }
}
