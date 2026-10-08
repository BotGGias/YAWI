package de.yawi.installer.core.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Checks a destination folder before the installation starts: syntax,
 * writability, free space, and whether it already holds files. Headless: the
 * UI translates the problem keys and shows them on the page, the CLI
 * reports them.
 *
 * <p>{@link #parse(String)} is the cheap, synchronous half (no file system);
 * {@link #check(String, long, long)} adds the file system probes and is meant
 * to run off the UI thread.
 */
public final class DestinationValidator {

    private static final Logger LOG = LoggerFactory.getLogger(DestinationValidator.class);

    public enum Severity { ERROR, WARNING }

    /**
     * One finding as a bundle key plus {@code MessageFormat} arguments. Byte
     * counts are passed as {@link Long} so the UI can format them for the
     * current language.
     */
    public record Problem(Severity severity, String key, Object... args) {
        public boolean isError() {
            return severity == Severity.ERROR;
        }
    }

    /** The parsed path, or the syntax problem that prevented parsing. */
    public record Parsed(Path path, Problem problem) {
        public boolean isValid() {
            return path != null;
        }
    }

    /**
     * Everything known about one candidate.
     *
     * @param path              the normalised absolute path, {@code null} for a syntax problem
     * @param problems          errors and warnings, in check order
     * @param elevationRequired the folder is not writable now, but would be with elevated rights (E12)
     * @param usableBytes       free space on the target file system, {@code -1} if unknown
     */
    public record Check(String text, Path path, List<Problem> problems, boolean elevationRequired, long usableBytes) {
        public Check {
            problems = List.copyOf(problems);
        }

        /** True if any error was found; "Next" stays locked then. */
        public boolean isBlocking() {
            return problems.stream().anyMatch(Problem::isError);
        }

        /** True if the folder exists and holds files: no error, but the user has to confirm. */
        public boolean needsConfirmation() {
            return problems.stream().anyMatch(p -> p.key().equals(NOT_EMPTY));
        }
    }

    public static final String EMPTY = "destination.error.empty";
    public static final String INVALID_PATH = "destination.error.invalidPath";
    public static final String NOT_ABSOLUTE = "destination.error.notAbsolute";
    public static final String IS_FILE = "destination.error.isFile";
    public static final String NOT_WRITABLE = "destination.error.notWritable";
    public static final String NOT_ENOUGH_SPACE = "destination.error.notEnoughSpace";
    public static final String SPACE_TIGHT = "destination.warning.spaceTight";
    public static final String NOT_EMPTY = "destination.warning.notEmpty";

    /** Manifest sizes are estimates; below this margin over the estimate we warn. */
    static final double SPACE_MARGIN = 1.1;

    private final Platform platform;

    public DestinationValidator(Platform platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    /**
     * The text as an absolute, normalised path with the platform's variables
     * and {@code ~} expanded - or why it is none: blank, not a path at all,
     * or relative (which would silently mean "relative to wherever the
     * installer was started").
     */
    public Parsed parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return new Parsed(null, new Problem(Severity.ERROR, EMPTY));
        }
        Path path;
        try {
            path = Path.of(platform.expandVariables(trimmed));
        } catch (InvalidPathException e) {
            LOG.debug("'{}' is not a path: {}", trimmed, e.getMessage());
            return new Parsed(null, new Problem(Severity.ERROR, INVALID_PATH, trimmed));
        }
        if (!path.isAbsolute()) {
            return new Parsed(null, new Problem(Severity.ERROR, NOT_ABSOLUTE, trimmed));
        }
        return new Parsed(path.normalize(), null);
    }

    /**
     * Parses and probes the file system. Touches the disk, so call it off the
     * UI thread.
     *
     * @param requiredBytes the install size of the selected components (E05-S04)
     * @param minFreeBytes  {@code destination/@minFreeBytes}, {@code -1} if not given
     */
    public Check check(String text, long requiredBytes, long minFreeBytes) {
        Parsed parsed = parse(text);
        if (!parsed.isValid()) {
            return new Check(text, null, List.of(parsed.problem()), false, -1);
        }
        Path path = parsed.path();
        List<Problem> problems = new ArrayList<>();
        boolean elevation = false;

        if (Files.isRegularFile(path)) {
            problems.add(new Problem(Severity.ERROR, IS_FILE, path.toString()));
        } else if (!platform.isWritable(path)) {
            // Outside the user's space a permission problem is E12's job, not an error here.
            if (platform.requiresElevation(path)) {
                elevation = true;
            } else {
                problems.add(new Problem(Severity.ERROR, NOT_WRITABLE, path.toString()));
            }
        }

        long usable = usableSpace(path);
        if (usable >= 0) {
            // The packager's minimum and the selection's size are both hard; the margin only warns.
            long needed = Math.max(requiredBytes, minFreeBytes);
            if (usable < needed) {
                problems.add(new Problem(Severity.ERROR, NOT_ENOUGH_SPACE, needed, usable));
            } else if (usable < (long) (requiredBytes * SPACE_MARGIN)) {
                problems.add(new Problem(Severity.WARNING, SPACE_TIGHT, requiredBytes, usable));
            }
        }

        if (isNonEmptyDirectory(path)) {
            problems.add(new Problem(Severity.WARNING, NOT_EMPTY, path.toString()));
        }
        Check check = new Check(text, path, problems, elevation, usable);
        LOG.debug("Destination {} checked: {} problem(s), elevation={}, usable={}",
                path, problems.size(), elevation, usable);
        return check;
    }

    private long usableSpace(Path path) {
        try {
            return platform.usableSpace(path);
        } catch (IOException | RuntimeException e) {
            LOG.debug("Free space of {} unknown: {}", path, e.toString());
            return -1;
        }
    }

    private static boolean isNonEmptyDirectory(Path path) {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(path)) {
            return entries.findAny().isPresent();
        } catch (IOException | SecurityException e) {
            LOG.debug("Cannot list {}: {}", path, e.toString());
            return false;
        }
    }
}
