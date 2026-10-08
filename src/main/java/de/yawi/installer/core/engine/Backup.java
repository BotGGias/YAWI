package de.yawi.installer.core.engine;

import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.state.RecordWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Where the originals of replaced files go: a folder below the
 * installer's own {@code .installer} directory, one per run. Originals are
 * <em>moved</em> there, not copied — same file system, no extra space, and
 * the file keeps its attributes for the way back. The folder appears with
 * the first {@link #save}; a run that replaces nothing leaves no trace.
 *
 * <p>Whether a path is saved at all is the record's business
 * ({@code InstallationRecord.tracks}): a path is saved once per run and
 * never when the run created it itself.
 */
public final class Backup {

    private static final Logger LOG = LoggerFactory.getLogger(Backup.class);
    private static final String DIRECTORY = "backup";
    /** Below the run's folder: copies of files outside the destination, prefixed by a hash of their path. */
    static final String EXTERNAL = "external";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    /** How long a rename is retried on Windows before giving up (a virus scanner may hold the file briefly). */
    static final Duration WINDOWS_RETRY = Duration.ofSeconds(10);

    private final Path destination;
    private final Path root;
    private final boolean retryMoves;

    Backup(Path destination, Path root, boolean retryMoves) {
        this.destination = destination.toAbsolutePath().normalize();
        this.root = root.toAbsolutePath().normalize();
        this.retryMoves = retryMoves;
    }

    /** The backup folder of the run that started at {@code startedAt}: {@code <dest>/.installer/backup/<stamp>}. */
    public static Path rootFor(Path destination, Instant startedAt) {
        return destination.toAbsolutePath().normalize().resolve(RecordWriter.DIRECTORY).resolve(DIRECTORY)
                .resolve(STAMP.format(startedAt));
    }

    /** The backup of a run, derived from its destination and start time. */
    public static Backup of(ExecutionContext context) {
        return new Backup(context.destination(), rootFor(context.destination(), context.record().startedAt()),
                context.platform().os() == OperatingSystem.WINDOWS);
    }

    /**
     * A backup of the same run under its own folder {@code <stamp>-<suffix>}:
     * the elevated segment (E12-S03) must not prune or discard the parent's.
     */
    public static Backup of(ExecutionContext context, String suffix) {
        Path root = rootFor(context.destination(), context.record().startedAt());
        return new Backup(context.destination(), root.resolveSibling(root.getFileName() + "-" + suffix),
                context.platform().os() == OperatingSystem.WINDOWS);
    }

    public Path root() {
        return root;
    }

    /**
     * Moves an existing target (file, directory or symlink, not followed)
     * into the backup and returns where it went — or empty if the target
     * lies outside the destination (the scratch directory), which is not
     * backed up.
     */
    public Optional<Path> save(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(destination)) {
            LOG.info("Not backing up {}: outside the destination", absolute);
            return Optional.empty();
        }
        Path saved = root.resolve(destination.relativize(absolute));
        Files.createDirectories(saved.getParent());
        move(absolute, saved);
        LOG.debug("Backed up {} -> {}", absolute, saved);
        return Optional.of(saved);
    }

    /**
     * Copies a file that lies <em>outside</em> the destination (a shortcut in
     * the menu or on the desktop) into the backup, so a rollback can
     * put the previous one back. A copy, not a move: the file stays where the
     * desktop sees it, and another file system is likely.
     */
    public Path saveExternal(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Path folder = root.resolve(EXTERNAL);
        Files.createDirectories(folder);
        // Named after the path's hash: two shortcuts may share a file name, and a Backup instance keeps no state.
        Path saved = folder.resolve(hash(absolute.toString()) + "-" + absolute.getFileName());
        Files.copy(absolute, saved, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        LOG.debug("Backed up {} -> {}", absolute, saved);
        return saved;
    }

    /** Moves a saved original back over whatever the installation put there. */
    public void restore(Path saved, Path target) throws IOException {
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
            deleteTree(target);
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        move(saved, target);
    }

    /**
     * Removes the empty directories the restores left behind, and the backup
     * folders themselves if nothing is left. Whatever could not be restored
     * stays and is returned.
     */
    public List<Path> prune() throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Path> leftBehind = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    try (Stream<Path> children = Files.list(p)) {
                        if (children.findAny().isEmpty()) {
                            Files.delete(p);
                        }
                    }
                } else {
                    leftBehind.add(p);
                }
            }
        }
        deleteIfEmpty(root.getParent());
        return leftBehind;
    }

    /** Deletes the whole backup after a successful installation. */
    public void discard() throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(root);
        }
        deleteIfEmpty(root.getParent());
    }

    private static String hash(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteIfEmpty(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            if (children.findAny().isEmpty()) {
                Files.delete(dir);
            }
        }
    }

    static void deleteTree(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /**
     * A rename; atomic where the file system offers it. On Windows a locked
     * file (virus scanner, a process that has not quite exited) is retried
     * for a while before the failure counts.
     */
    private void move(Path from, Path to) throws IOException {
        Instant deadline = retryMoves ? Instant.now().plus(WINDOWS_RETRY) : Instant.now();
        while (true) {
            try {
                try {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (FileSystemException e) {
                if (!retryMoves || Instant.now().isAfter(deadline)) {
                    throw e;
                }
                LOG.info("Retrying move of {} ({})", from, e.getMessage());
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
