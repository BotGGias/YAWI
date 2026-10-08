package de.yawi.installer.core.state;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What an installation did, in the order it did it: product,
 * destination, selection, the inputs, and one {@link Entry} per created
 * file or directory and per step start and end.
 *
 * <p>Steps append entries while they run; a {@link RecordWriter} attached
 * through {@link #onEntry} puts each one on disk immediately, so the record
 * is complete up to the last entry even after a crash. Paths inside the
 * destination are kept relative to it (with {@code /}), so the record stays
 * valid if the folder is moved.
 *
 * <p>Besides created paths the record knows what an installation
 * <em>changed</em>: files it replaced (moved to the backup first, E11-S05)
 * and modes it set. Walked backwards, the entries are the rollback.
 */
public final class InstallationRecord {

    public static final int FORMAT_VERSION = 1;

    /** One thing the installation did. */
    public sealed interface Entry {
    }

    /** @param path relative to the destination with {@code /}, or absolute if outside it */
    public record CreatedFile(String path) implements Entry {
    }

    public record CreatedDirectory(String path) implements Entry {
    }

    /**
     * A file (or directory, or symlink) that existed before and was moved to
     * the backup before the installation wrote its own; the rollback moves it
     * back.
     *
     * @param backup where the original went, relative to the destination
     */
    public record ReplacedFile(String path, String backup) implements Entry {
    }

    /** @param mode the POSIX mode <em>before</em> the change, octal ({@code 755}) */
    public record ModeChanged(String path, String mode) implements Entry {
    }

    public record StepStarted(String stepId) implements Entry {
    }

    /** @param message the failure's technical text, or null */
    public record StepFinished(String stepId, String outcome, String message) implements Entry {
    }

    /**
     * The run completed successfully at {@code at}; written last.
     * A file whose modification time lies after it was changed since the
     * installation. A rolled-back run never writes one.
     */
    public record RunFinished(Instant at) implements Entry {
    }

    private final String productId;
    private final String productVersion;
    private final Instant startedAt;
    private final Path destination;
    private final List<String> components;
    private final Map<String, String> inputs;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> trackedPaths = new HashSet<>();
    private final List<Consumer<Entry>> sinks = new ArrayList<>();

    /**
     * @param inputs the values of the selected components' inputs; pass them
     *               through {@link #withoutSecrets} first
     */
    public InstallationRecord(String productId, String productVersion, Instant startedAt, Path destination,
                              Collection<String> components, Map<String, String> inputs) {
        this.productId = Objects.requireNonNull(productId, "productId");
        this.productVersion = Objects.requireNonNull(productVersion, "productVersion");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.destination = destination.toAbsolutePath().normalize();
        this.components = List.copyOf(components);
        this.inputs = Map.copyOf(new LinkedHashMap<>(inputs));
    }

    /**
     * The inputs minus anything that looks like a credential, judged by id
     * and label: the record must not hold passwords or tokens.
     */
    public static Map<String, String> withoutSecrets(Map<String, String> inputs, Map<String, String> labelsById) {
        Map<String, String> safe = new LinkedHashMap<>();
        inputs.forEach((id, value) -> {
            if (!looksSecret(id) && !looksSecret(labelsById.getOrDefault(id, ""))) {
                safe.put(id, value);
            }
        });
        return safe;
    }

    static boolean looksSecret(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("passwort") || lower.contains("secret")
                || lower.contains("token") || lower.contains("credential");
    }

    public String productId() {
        return productId;
    }

    public String productVersion() {
        return productVersion;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Path destination() {
        return destination;
    }

    public List<String> components() {
        return components;
    }

    public Map<String, String> inputs() {
        return inputs;
    }

    /** The entries so far, in order. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Receives every entry the moment it is added; the writer registers here. */
    public synchronized void onEntry(Consumer<Entry> sink) {
        sinks.add(Objects.requireNonNull(sink, "sink"));
    }

    public void fileCreated(Path path) {
        add(new CreatedFile(relativize(path)));
    }

    public void directoryCreated(Path path) {
        add(new CreatedDirectory(relativize(path)));
    }

    public void fileReplaced(Path path, Path backup) {
        add(new ReplacedFile(relativize(path), relativize(backup)));
    }

    public void modeChanged(Path path, String previousMode) {
        add(new ModeChanged(relativize(path), previousMode));
    }

    /**
     * Whether this installation already owns the path: it created it or
     * replaced it (and thereby backed the original up). A second write to
     * such a path must not back it up again, or the backup would hold the
     * installation's own intermediate file instead of the original.
     */
    public synchronized boolean tracks(Path path) {
        return trackedPaths.contains(relativize(path));
    }

    public void stepStarted(String stepId) {
        add(new StepStarted(stepId));
    }

    public void stepFinished(String stepId, String outcome, String message) {
        add(new StepFinished(stepId, outcome, message));
    }

    public void runFinished(Instant at) {
        add(new RunFinished(at));
    }

    /** When the run completed successfully, if it did and the record knows it (records before E14-S03 do not). */
    public Optional<Instant> finishedAt() {
        List<Entry> all = entries();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i) instanceof RunFinished f) {
                return Optional.of(f.at());
            }
        }
        return Optional.empty();
    }

    /** Re-adds an entry read from disk; used by {@link RecordReader}. */
    public synchronized void add(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        entries.add(entry);
        switch (entry) {
            case CreatedFile f -> trackedPaths.add(f.path());
            case ReplacedFile r -> trackedPaths.add(r.path());
            default -> {
            }
        }
        for (Consumer<Entry> sink : sinks) {
            sink.accept(entry);
        }
    }

    /**
     * Files and directories created, in creation order. (after a
     * successful update, replaced paths belong to the installation as well;
     * the uninstaller will want {@link ReplacedFile#path()} here too.)
     */
    public List<String> createdPaths() {
        return entries().stream()
                .map(e -> e instanceof CreatedFile f ? f.path() : e instanceof CreatedDirectory d ? d.path() : null)
                .filter(Objects::nonNull)
                .toList();
    }

    /** The absolute path of a recorded path, against this record's destination. */
    public Path resolve(String recordedPath) {
        Path path = Path.of(recordedPath);
        return path.isAbsolute() ? path : destination.resolve(recordedPath);
    }

    /** The recorded form of a path: relative to the destination with {@code /}, absolute outside it. */
    public String relativize(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(destination)) {
            return absolute.toString();
        }
        Path relative = destination.relativize(absolute);
        StringBuilder sb = new StringBuilder();
        for (Path part : relative) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
