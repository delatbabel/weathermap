package org.weathermap.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --quiet} has to mean nothing on stdout, because cron mails whatever a
 * job prints and a nightly mail saying it worked is a nightly mail nobody
 * reads.
 */
class RunLogTest {

    /** Runs something with stdout and stderr captured. */
    private static String[] capturing(Runnable work) {
        final PrintStream realOut = System.out;
        final PrintStream realErr = System.err;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            work.run();
        }
        finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
        return new String[]{out.toString(StandardCharsets.UTF_8),
                            err.toString(StandardCharsets.UTF_8)};
    }

    @Test
    void quietPrintsNothingAndWritesEverythingToTheFile(@TempDir Path dir) throws Exception {
        final Path logFile = dir.resolve("weathermap.log");

        final String[] streams = capturing(() -> {
            try (RunLog log = RunLog.open(true, logFile)) {
                log.say("area      85,-8,141,26");
                log.say("posted    17895695668004550");
            }
        });

        assertEquals("", streams[0], "stdout must be empty under --quiet");
        final String written = Files.readString(logFile);
        assertTrue(written.contains("area      85,-8,141,26"), written);
        assertTrue(written.contains("posted    17895695668004550"), written);
    }

    /**
     * Quiet means quiet: a failure goes to the log and nowhere else.
     *
     * <p>So a crontab entry produces no mail whether the run worked or not. The
     * exit status still reports the failure, which is what a wrapper script
     * should be testing.</p>
     */
    @Test
    void aFailureGoesToTheLogAndNotTheTerminal(@TempDir Path dir) throws Exception {
        final Path logFile = dir.resolve("weathermap.log");

        final String[] streams = capturing(() -> {
            try (RunLog log = RunLog.open(true, logFile)) {
                log.problem("weathermap: no profile called \"nope\"");
            }
        });

        assertEquals("", streams[0], "nothing on stdout");
        assertEquals("", streams[1], "and nothing on stderr either");
        assertTrue(Files.readString(logFile).contains("no profile called"));
    }

    /** Without --quiet a failure still goes to stderr, as it always did. */
    @Test
    void anOrdinaryRunStillReportsFailuresOnStderr() {
        final String[] streams = capturing(() -> {
            try (RunLog log = RunLog.open(false, null)) {
                log.problem("weathermap: no profile called \"nope\"");
            }
        });

        assertTrue(streams[1].contains("no profile called"), streams[1]);
    }

    /**
     * With no log to write to, a failure goes back to stderr.
     *
     * <p>Silence is bought by the log having the failures instead. If the log
     * could not be opened there is nowhere else for them, and losing them
     * entirely would be the one outcome worse than a mail.</p>
     */
    @Test
    void aFailureIsNotLostWhenTheLogCannotBeOpened(@TempDir Path dir) throws Exception {
        // A directory where the log file should be: opening it must fail.
        final Path blocked = dir.resolve("weathermap.log");
        Files.createDirectory(blocked);

        final String[] streams = capturing(() -> {
            try (RunLog log = RunLog.open(true, blocked)) {
                log.say("this is only commentary");
                log.problem("weathermap: something went wrong");
            }
        });

        assertEquals("", streams[0], "the commentary still goes nowhere");
        assertTrue(streams[1].contains("something went wrong"), streams[1]);
    }

    /**
     * The logging of every other class goes to the file too.
     *
     * <p>{@code java.util.logging} writes to stderr by default, so a retry
     * inside {@code Http} printed a WARNING from a job that had asked for
     * silence - output that came from neither the command line nor anything it
     * called directly.</p>
     */
    @Test
    void frameworkLoggingIsCapturedRatherThanPrinted(@TempDir Path dir) throws Exception {
        final Path logFile = dir.resolve("weathermap.log");

        final String[] streams = capturing(() -> {
            try (RunLog log = RunLog.open(true, logFile)) {
                Logger.getLogger("org.weathermap.util.Http")
                        .warning("attempt 1 failed for https://example; retrying");
            }
        });

        assertEquals("", streams[0]);
        assertTrue(Files.readString(logFile).contains("attempt 1 failed"),
                   "the warning should be in the log");
    }

    /**
     * The root logger is global, so what is borrowed is given back.
     *
     * <p>Quiet takes the console handlers off to stop them writing to the
     * terminal. Leaving them off would silence everything the process did
     * afterwards, which is a side effect a command-line flag has no business
     * having.</p>
     */
    @Test
    void theLoggingConfigurationIsPutBack(@TempDir Path dir) {
        final Logger root = Logger.getLogger("");
        final Handler[] before = root.getHandlers();

        try (RunLog log = RunLog.open(true, dir.resolve("weathermap.log"))) {
            log.say("something");
        }

        assertEquals(before.length, root.getHandlers().length,
                     "the root logger should have the handlers it started with");
        for (Handler handler : before) {
            assertTrue(java.util.Arrays.asList(root.getHandlers()).contains(handler),
                       "a handler was not put back: " + handler);
        }
    }

    /** Without --quiet and without --log, nothing writes a file at all. */
    @Test
    void anOrdinaryRunLeavesNoLogBehind(@TempDir Path dir) {
        final Path logFile = dir.resolve("weathermap.log");

        final String[] streams = capturing(() -> {
            try (RunLog log = RunLog.open(false, null)) {
                log.say("area      85,-8,141,26");
            }
        });

        assertTrue(streams[0].contains("area"), "it still prints: " + streams[0]);
        assertFalse(Files.exists(logFile));
    }

    /** A nightly job appends forever, so the file cannot be allowed to. */
    @Test
    void anOversizedLogIsMovedAside(@TempDir Path dir) throws Exception {
        final Path logFile = dir.resolve("weathermap.log");
        Files.writeString(logFile, "x".repeat(2 * 1024 * 1024));

        try (RunLog log = RunLog.open(true, logFile)) {
            log.say("a new run");
        }

        assertTrue(Files.exists(dir.resolve("weathermap.log.1")), "the old log should be kept");
        final String now = Files.readString(logFile);
        assertTrue(now.contains("a new run"), now);
        assertTrue(now.length() < 1000, "the live log should start fresh");
    }
}
