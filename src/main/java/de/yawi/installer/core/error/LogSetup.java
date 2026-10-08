package de.yawi.installer.core.error;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the log file is. Logging starts in the temp directory
 * because Logback configures itself before the platform or the manifest are
 * known; {@link #redirect} moves it to {@code Platform.logDir(productId)}
 * once they are, {@link #redirectToFile} to the exact file the caller named
 * with {@code --log=}. The only place outside {@code logback.xml} that
 * touches Logback.
 */
public final class LogSetup {

    /** Context property {@code logback.xml} reads; unset means the temp directory. */
    public static final String PROPERTY = "yawi.logDir";
    /** Context property for the file name inside that directory; unset means {@link #FILE_NAME}. */
    public static final String FILE_PROPERTY = "yawi.logFile";
    /** Context property for the console appender's threshold; unset means everything (the file gets it all anyway). */
    public static final String CONSOLE_LEVEL_PROPERTY = "yawi.consoleLevel";
    public static final String FILE_NAME = "installer.log";
    private static final String CONFIG = "/logback.xml";

    // The elevated process sets both as system properties before the first logger is created, so
    // Logback never opens a root-owned installer.log in the temp directory; logback.xml reads them the same way.
    private static volatile Path currentDir = Path.of(System.getProperty(PROPERTY,
            System.getProperty("java.io.tmpdir") + java.io.File.separator + "yawi-installer"));
    private static volatile String currentFile = System.getProperty(FILE_PROPERTY, FILE_NAME);
    private static volatile String consoleLevel;

    private LogSetup() {
    }

    /** The log file in use right now; the finish page and the error dialog open this. */
    public static Path currentLogFile() {
        return currentDir.resolve(currentFile);
    }

    /**
     * Continues logging in {@code dir} (file name {@link #FILE_NAME}): creates
     * it, re-runs {@code logback.xml} with {@link #PROPERTY} set, and leaves a
     * pointer line in the old file. If the directory cannot be created or
     * Logback is not the binding, the log stays where it is and the old
     * location is returned.
     */
    public static synchronized Path redirect(Path dir) {
        return redirect(dir, FILE_NAME);
    }

    /** Continues logging in exactly {@code file}; the silent mode's {@code --log=}. */
    public static synchronized Path redirectToFile(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path dir = absolute.getParent();
        return redirect(dir == null ? absolute.getRoot() : dir, absolute.getFileName().toString());
    }

    /**
     * Quietens the console for the silent mode: only messages at {@code level}
     * or above reach stdout, the file keeps everything. Takes effect with the
     * next {@link #redirect}; call before it.
     */
    public static synchronized void setConsoleLevel(String level) {
        consoleLevel = level;
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            reconfigure(context, currentDir, currentFile);
        }
    }

    private static Path redirect(Path dir, String fileName) {
        Path target = dir.toAbsolutePath().normalize();
        if (target.equals(currentDir) && fileName.equals(currentFile)) {
            return currentLogFile();
        }
        Logger log = LoggerFactory.getLogger(LogSetup.class);
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            log.warn("Not running on Logback; log stays at {}", currentLogFile());
            return currentLogFile();
        }
        try {
            Files.createDirectories(target);
        } catch (IOException | SecurityException e) {
            log.warn("Cannot create log directory {} ({}); log stays at {}", target, e.toString(), currentLogFile());
            return currentLogFile();
        }
        if (LogSetup.class.getResource(CONFIG) == null) {
            log.warn("{} not on the classpath; log stays at {}", CONFIG, currentLogFile());
            return currentLogFile();
        }
        log.info("Log moves to {}", target.resolve(fileName));
        if (!reconfigure(context, target, fileName)) {
            // reset() left the context empty; fall back to the previous location.
            reconfigure(context, currentDir, currentFile);
            LoggerFactory.getLogger(LogSetup.class).warn("Cannot reconfigure logging for {}", target);
            return currentLogFile();
        }
        currentDir = target;
        currentFile = fileName;
        LoggerFactory.getLogger(LogSetup.class).info("Log continues here (moved from the temp directory)");
        return currentLogFile();
    }

    /** Re-runs {@code logback.xml} with the given location; false if Logback rejected it. */
    private static boolean reconfigure(LoggerContext context, Path dir, String fileName) {
        URL config = LogSetup.class.getResource(CONFIG);
        if (config == null) {
            return false;
        }
        context.reset();
        context.putProperty(PROPERTY, dir.toString());
        context.putProperty(FILE_PROPERTY, fileName);
        if (consoleLevel != null) {
            context.putProperty(CONSOLE_LEVEL_PROPERTY, consoleLevel);
        }
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try {
            configurator.doConfigure(config);
            return true;
        } catch (JoranException e) {
            // nothing more to do here: Logback prints its own status on stderr
            return false;
        }
    }
}
