package de.yawi.installer.core.engine;

import de.yawi.installer.core.elevation.ElevatedUninstall;
import de.yawi.installer.core.elevation.ElevatedWorker;
import de.yawi.installer.core.elevation.Elevation;
import de.yawi.installer.core.elevation.UninstallPlan;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.IntegrationConfig;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRecord.CreatedDirectory;
import de.yawi.installer.core.state.InstallationRecord.CreatedFile;
import de.yawi.installer.core.state.InstallationRecord.Entry;
import de.yawi.installer.core.state.InstallationRecord.ReplacedFile;
import de.yawi.installer.core.state.InstallationRecord.StepStarted;
import de.yawi.installer.core.state.InstallationRegistry;
import de.yawi.installer.core.state.RecordSelection;
import de.yawi.installer.core.state.RecordWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Removes an installation from its {@link InstallationRecord}.
 * {@link #inspect()} says what would happen without touching anything;
 * {@link #run} does it: the reverse commands of every {@code run-command}
 * that ran (while their files are still there), then the recorded files,
 * then - if asked - what the user changed or added, then the record itself,
 * the register entry, and finally the directories that are left empty.
 *
 * <p>What the installer did not put there is never deleted unasked: a file
 * whose modification time lies after the run's end counts as changed by the
 * user, a file the record does not know as the user's data. Both stay
 * unless the {@link Options} say otherwise, and both are reported. A failure
 * on one item does not stop the rest; the record and the register entry stay
 * when anything failed, so a second attempt can pick up where this one left.
 * There is no rollback of an uninstall.
 *
 * <p>The record only holds the last run: after an update, files the first
 * installation created and the update did not touch, and directories that
 * already existed, are not in it. Recorded {@link ReplacedFile}s therefore
 * count as installed files, and the empty parents of everything deleted are
 * removed as well, up to the destination.
 */
public final class Uninstaller {

    private static final Logger LOG = LoggerFactory.getLogger(Uninstaller.class);
    /** Slack for file systems with coarse timestamps (FAT: 2 s) when comparing against the run's end. */
    static final Duration MODIFIED_TOLERANCE = Duration.ofSeconds(2);

    /**
     * @param deleteModified   delete recorded files even if they were changed since the installation
     * @param deleteUnrecorded delete files below the destination that the record does not know
     */
    public record Options(boolean deleteModified, boolean deleteUnrecorded) {
        /** The default: nothing the user changed or added goes. */
        public static final Options SAFE = new Options(false, false);
        /** Everything below the destination goes ({@code --purge}). */
        public static final Options PURGE = new Options(true, true);
    }

    /**
     * What the uninstall would touch, paths in the record's form (relative
     * to the destination with {@code /}, absolute outside it).
     *
     * @param files              recorded files that exist, in record order ({@code .installer/} excluded)
     * @param modified           the subset of {@code files} changed since the run's end
     * @param unrecorded         files below the destination the record does not know
     * @param directories        recorded directories, in record order ({@code .installer} excluded)
     * @param steps              {@code run-command} steps that ran and still exist in the manifest, newest first
     * @param stepsWithoutReverse the subset of {@code steps} whose reverse cannot run
     * @param associations       {@code fileAssociation} steps that ran and still exist in the manifest (E13-S02)
     * @param pathEntries        the {@code pathEntry} step id if it ran and the manifest still declares entries (E13-S03)
     * @param finishedAt         the run's end the modification check uses
     */
    public record Inventory(List<String> files, List<String> modified, List<String> unrecorded,
                            List<String> directories, List<String> steps, List<String> stepsWithoutReverse,
                            List<String> associations, List<String> pathEntries, Instant finishedAt) {
        public Inventory {
            files = List.copyOf(files);
            modified = List.copyOf(modified);
            unrecorded = List.copyOf(unrecorded);
            directories = List.copyOf(directories);
            steps = List.copyOf(steps);
            stepsWithoutReverse = List.copyOf(stepsWithoutReverse);
            associations = List.copyOf(associations);
            pathEntries = List.copyOf(pathEntries);
        }
    }

    /**
     * What the uninstall did.
     *
     * @param deleted             files, trees and directories removed
     * @param stepsReversed       {@code run-command} steps whose reverse commands ran
     * @param keptModified        changed files left in place (record order)
     * @param keptUnrecorded      user files left in place
     * @param stepsWithoutReverse ids of steps that ran but could not be reversed
     * @param failures            one line per item that could not be removed
     * @param cancelled           whether the user stopped it; what was deleted stays deleted
     * @param destinationRemoved  whether the destination folder itself is gone
     */
    public record Report(int deleted, int stepsReversed, List<String> keptModified, List<String> keptUnrecorded,
                         List<String> stepsWithoutReverse, List<String> failures, boolean cancelled,
                         boolean destinationRemoved) {
        public Report {
            keptModified = List.copyOf(keptModified);
            keptUnrecorded = List.copyOf(keptUnrecorded);
            stepsWithoutReverse = List.copyOf(stepsWithoutReverse);
            failures = List.copyOf(failures);
        }

        /** Everything the run set out to delete is gone; deliberately kept files and steps without a reverse aside. */
        public boolean clean() {
            return failures.isEmpty() && !cancelled;
        }

        public boolean keptAnything() {
            return !keptModified.isEmpty() || !keptUnrecorded.isEmpty();
        }
    }

    private final InstallManifest manifest;
    private final Platform platform;
    private final InstallationRecord record;
    private final Optional<InstallationRegistry> registry;
    /** How to obtain administrator rights when the destination needs them; empty = never elevate. */
    private final Optional<Elevation> elevation;
    private final Path destination;

    /**
     * @param manifest the installer's manifest: the {@code <rollback>} commands come from here, looked up by step id
     * @param record   the installation's record, read from {@code <dest>/.installer/record.xml}
     * @param registry where the installation is registered; empty for runs that must leave no trace outside
     *                 the destination (tests)
     */
    public Uninstaller(InstallManifest manifest, Platform platform, InstallationRecord record,
                       Optional<InstallationRegistry> registry) {
        this(manifest, platform, record, registry, Optional.empty());
    }

    /**
     * @param elevation how to obtain administrator rights when the destination needs them (E14-S03); empty
     *                  runs entirely in this process (tests, a user-space destination)
     */
    public Uninstaller(InstallManifest manifest, Platform platform, InstallationRecord record,
                       Optional<InstallationRegistry> registry, Optional<Elevation> elevation) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.record = Objects.requireNonNull(record, "record");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.elevation = Objects.requireNonNull(elevation, "elevation");
        this.destination = record.destination();
    }

    public InstallationRecord record() {
        return record;
    }

    // --- inventory ----------------------------------------------------------

    /** Looks, never touches. */
    public Inventory inspect() {
        Instant finishedAt = finishedAt();
        Instant threshold = finishedAt.plus(MODIFIED_TOLERANCE);
        Set<String> recorded = new LinkedHashSet<>();
        List<String> directories = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        List<Entry> entries = record.entries();
        for (Entry entry : entries) {
            switch (entry) {
                case CreatedFile f -> recorded.add(f.path());
                case ReplacedFile r -> recorded.add(r.path());
                case CreatedDirectory d -> {
                    if (!insideInstallerDir(d.path())) {
                        directories.add(d.path());
                    }
                }
                default -> {
                }
            }
        }
        // Only command steps have something to reverse; file steps are undone by deleting what they recorded.
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof StepStarted s && !steps.contains(s.stepId())
                    && manifest.step(s.stepId()).map(InstallStep.RunCommand.class::isInstance).orElse(false)) {
                steps.add(s.stepId());
            }
        }

        // File associations (E13-S02): the created files are deleted below; the registry / mimeapps.list
        // changes are reversed from the manifest, so only associations the manifest still declares count.
        List<String> associations = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof StepStarted s) {
                Optional<String> ext = InstallStep.FileAssociation.extensionOf(s.stepId());
                if (ext.isPresent() && !associations.contains(s.stepId()) && association(ext.get()).isPresent()) {
                    associations.add(s.stepId());
                }
            }
        }

        // PATH entries (E13-S03): the marked block / registry change is reversed from the manifest, so it
        // only counts while the manifest still declares entries. One implicit step for all entries.
        List<String> pathEntries = new ArrayList<>();
        if (!manifest.integration().pathEntries().isEmpty()) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i) instanceof StepStarted s && InstallStep.PathEntries.isPathStep(s.stepId())
                        && pathEntries.isEmpty()) {
                    pathEntries.add(s.stepId());
                }
            }
        }

        List<String> files = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        Set<Path> recordedTrees = new LinkedHashSet<>();
        for (String path : recorded) {
            if (insideInstallerDir(path)) {
                continue;
            }
            Path absolute = record.resolve(path);
            if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            files.add(path);
            if (Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                recordedTrees.add(absolute);
            } else if (modifiedAfter(absolute, threshold)) {
                modified.add(path);
            }
        }

        List<String> unrecorded = unrecordedFiles(files, recordedTrees);
        List<String> withoutReverse = steps.stream().filter(id -> reverseBlocked(id).isPresent()).toList();
        return new Inventory(files, modified, unrecorded, directories, steps, withoutReverse, associations,
                pathEntries, finishedAt);
    }

    /** The {@code <fileAssociation>} of the manifest for an extension, if it still declares one. */
    private Optional<IntegrationConfig.FileAssociation> association(String extension) {
        return manifest.integration().fileAssociations().stream()
                .filter(a -> a.extension().equals(extension)).findFirst();
    }

    /** The run's end from the record, else the record file's own modification time, else the run's start. */
    private Instant finishedAt() {
        Optional<Instant> recorded = record.finishedAt();
        if (recorded.isPresent()) {
            return recorded.get();
        }
        Path file = RecordWriter.defaultFile(destination);
        try {
            if (Files.isRegularFile(file)) {
                return Files.getLastModifiedTime(file).toInstant();
            }
        } catch (IOException e) {
            LOG.debug("Cannot read the modification time of {}: {}", file, e.toString());
        }
        return record.startedAt();
    }

    private static boolean modifiedAfter(Path file, Instant threshold) {
        try {
            return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(threshold);
        } catch (IOException e) {
            LOG.debug("Cannot read the modification time of {}: {}", file, e.toString());
            return false;
        }
    }

    /** Files below the destination that are neither recorded nor inside a recorded tree nor the installer's own. */
    private List<String> unrecordedFiles(List<String> recordedFiles, Set<Path> recordedTrees) {
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Set<String> known = new LinkedHashSet<>(recordedFiles);
        Path installerDir = destination.resolve(RecordWriter.DIRECTORY);
        List<String> found = new ArrayList<>();
        try {
            Files.walkFileTree(destination, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(installerDir) || recordedTrees.contains(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String path = record.relativize(file);
                    if (!known.contains(path)) {
                        found.add(path);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    LOG.warn("Cannot inspect {}: {}", file, e.toString());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("Cannot inspect {}: {}", destination, e.toString());
        }
        return found;
    }

    private static boolean insideInstallerDir(String recordedPath) {
        return recordedPath.equals(RecordWriter.DIRECTORY) || recordedPath.startsWith(RecordWriter.DIRECTORY + "/");
    }

    private Optional<Undo.NoReverse> reverseBlocked(String stepId) {
        Optional<InstallStep> definition = manifest.step(stepId);
        if (definition.isEmpty()) {
            return Optional.of(Undo.NoReverse.NONE_DECLARED);
        }
        return Undo.reverseBlocked(context(ProgressListener.NOOP, new CancellationToken()), definition.get());
    }

    /** A context for the reverse commands: placeholders from the record's selection and inputs. */
    private ExecutionContext context(ProgressListener listener, CancellationToken token) {
        RecordSelection selection = RecordSelection.from(manifest, record);
        Set<String> selected = manifest.resolveSelection(selection.components());
        Map<String, String> inputs = InstallRunner.selectedInputs(manifest, selected, selection.inputs());
        Placeholders placeholders = Placeholders.of(manifest, platform, destination, inputs, selected);
        return new ExecutionContext(manifest, platform, destination, inputs, selected, placeholders,
                new BundledArtifacts(), record, listener, FailurePrompt.ABORT_ALWAYS, token);
    }

    // --- dry run --------------------------------------------------------------

    /** One line per action, in execution order, for {@code --dry-run} and the log. */
    public List<String> dryRun(Options options) {
        Inventory inventory = inspect();
        List<String> lines = new ArrayList<>();
        for (String step : inventory.steps()) {
            lines.add(inventory.stepsWithoutReverse().contains(step)
                    ? "step " + step + ": no reverse operation, cannot be undone"
                    : "step " + step + ": run reverse commands");
        }
        for (String association : inventory.associations()) {
            lines.add(association + ": remove the file association");
        }
        for (String path : inventory.pathEntries()) {
            lines.add(path + ": remove the PATH entries");
        }
        for (String file : inventory.files()) {
            if (inventory.modified().contains(file) && !options.deleteModified()) {
                lines.add("keep " + file + " (changed since the installation)");
            } else {
                lines.add("delete " + file);
            }
        }
        for (String file : inventory.unrecorded()) {
            lines.add(options.deleteUnrecorded() ? "delete " + file + " (not installed by the installer)"
                    : "keep " + file + " (not installed by the installer)");
        }
        lines.add("delete " + RecordWriter.DIRECTORY + " (record and backups)");
        registry.ifPresent(r -> lines.add("unregister " + destination));
        for (int i = inventory.directories().size() - 1; i >= 0; i--) {
            lines.add("remove " + inventory.directories().get(i) + " if empty");
        }
        lines.add("remove " + destination + " if empty");
        return lines;
    }

    // --- run ------------------------------------------------------------------

    /** Never throws for a failed item; the {@link Report} carries every failure. */
    public Report run(Options options, ProgressListener listener, CancellationToken token) {
        Objects.requireNonNull(options, "options");
        ProgressListener out = listener == null ? ProgressListener.NOOP : listener;
        CancellationToken cancellation = token == null ? new CancellationToken() : token;
        if (shouldElevate()) {
            return runElevated(options, out, cancellation);
        }
        Run run = new Run(options, out, cancellation);
        return run.execute();
    }

    /** The destination needs rights this process does not have, and we may ask for them (E14-S03). */
    private boolean shouldElevate() {
        return elevation.isPresent() && !platform.isElevated() && !ElevatedWorker.isChild()
                && platform.requiresElevation(destination);
    }

    /**
     * Runs the whole uninstall in an elevated child (E14-S03): it removes the
     * files it has the rights for, we take the register entry - which lives in
     * the caller's profile - out here afterwards. The child never registers.
     */
    private Report runElevated(Options options, ProgressListener listener, CancellationToken token) {
        LOG.info("Uninstall of {} needs administrator rights: running elevated", destination);
        UninstallPlan plan = new UninstallPlan(destination, options.deleteModified(), options.deleteUnrecorded(),
                manifest.origin(), callerEnv(), callerProperties());
        try (ElevatedUninstall child = ElevatedUninstall.prepare(elevation.get(), platform, manifest, plan,
                listener, token)) {
            child.start();
            child.go();
            Report report = child.await();
            if (report.clean()) {
                removeRegisterEntry();
            }
            return report;
        }
    }

    /** Takes the register entry out; the register lives in the caller's profile, so the unelevated side does it. */
    private void removeRegisterEntry() {
        registry.ifPresent(r -> {
            try {
                if (r.unregister(destination)) {
                    LOG.info("Removed the register entry of {} from {}", destination, r.directory());
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not remove the register entry of {} from {}: {}", destination, r.directory(),
                        e.toString());
            }
        });
    }

    /** The environment variables the child needs to resolve the caller's locations, like the install hand-over. */
    private Map<String, String> callerEnv() {
        Map<String, String> env = new java.util.LinkedHashMap<>();
        for (String name : List.of("HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "XDG_DATA_HOME",
                "XDG_CONFIG_HOME", "XDG_STATE_HOME", "PATH", "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432",
                "ProgramData", "SystemRoot")) {
            platform.environment().env(name).ifPresent(v -> env.put(name, v));
        }
        return env;
    }

    private Map<String, String> callerProperties() {
        Map<String, String> properties = new java.util.LinkedHashMap<>();
        for (String name : List.of("user.home", "java.io.tmpdir")) {
            platform.environment().property(name).ifPresent(v -> properties.put(name, v));
        }
        return properties;
    }

    /** One execution; the counters live here so {@link Uninstaller} stays reusable. */
    private final class Run {
        private final Options options;
        private final ProgressListener listener;
        private final CancellationToken token;
        private final List<String> keptModified = new ArrayList<>();
        private final List<String> keptUnrecorded = new ArrayList<>();
        private final List<String> stepsWithoutReverse = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private final Set<Path> parentsToPrune = new TreeSet<>(Comparator.comparingInt(Path::getNameCount).reversed()
                .thenComparing(Comparator.naturalOrder()));
        private int deleted;
        private final List<Path> removedFiles = new ArrayList<>();
        private int stepsReversed;
        private int done;
        private int total;
        private boolean cancelled;

        Run(Options options, ProgressListener listener, CancellationToken token) {
            this.options = options;
            this.listener = listener;
            this.token = token;
        }

        Report execute() {
            Inventory inventory = inspect();
            total = inventory.steps().size() + inventory.associations().size() + inventory.pathEntries().size()
                    + inventory.files().size()
                    + (options.deleteUnrecorded() ? inventory.unrecorded().size() : 0)
                    + 2 + inventory.directories().size() + 1;
            LOG.info("Uninstalling {} {} from {}: {} step(s), {} file(s), {} unrecorded, {} directories",
                    record.productId(), record.productVersion(), destination, inventory.steps().size(),
                    inventory.files().size(), inventory.unrecorded().size(), inventory.directories().size());
            listener.uninstallStarted(total);
            boolean destinationRemoved = false;
            try {
                reverseSteps(inventory);
                reverseAssociations(inventory);
                reversePathEntries(inventory);
                deleteFiles(inventory);
                Undo.refreshMenus(platform, removedFiles);
                Undo.refreshMimeDatabase(platform, removedFiles);
                deleteUnrecorded(inventory);
                if (failures.isEmpty()) {
                    deleteInstallerDir();
                    unregister();
                } else {
                    LOG.warn("Keeping {} and the register entry: {} item(s) could not be removed",
                            RecordWriter.DIRECTORY, failures.size());
                    progress(RecordWriter.DIRECTORY);
                    progress("register");
                }
                deleteDirectories(inventory);
                destinationRemoved = deleteIfEmpty(destination, destination.toString());
                progress(destination.toString());
            } catch (de.yawi.installer.core.error.CancelledException e) {
                cancelled = true;
                LOG.warn("Uninstall cancelled after {} of {} item(s); nothing is restored", done, total);
            }
            Report report = new Report(deleted, stepsReversed, keptModified, keptUnrecorded, stepsWithoutReverse,
                    failures, cancelled, destinationRemoved);
            LOG.info("Uninstall finished: {} deleted, {} step(s) reversed, {} changed file(s) kept, "
                            + "{} user file(s) kept, {} step(s) without reverse operation, {} failure(s){}",
                    deleted, stepsReversed, keptModified.size(), keptUnrecorded.size(), stepsWithoutReverse.size(),
                    failures.size(), cancelled ? ", cancelled" : "");
            keptModified.forEach(p -> LOG.info("Kept (changed since the installation): {}", p));
            keptUnrecorded.forEach(p -> LOG.info("Kept (not installed by the installer): {}", p));
            failures.forEach(f -> LOG.warn("Could not remove: {}", f));
            listener.uninstallFinished(report);
            return report;
        }

        private void reverseSteps(Inventory inventory) {
            if (inventory.steps().isEmpty()) {
                return;
            }
            ExecutionContext context = context(listener, token);
            for (String stepId : inventory.steps()) {
                token.checkpoint();
                Optional<InstallStep> definition = manifest.step(stepId);
                Optional<Undo.NoReverse> blocked = definition.isEmpty()
                        ? Optional.of(Undo.NoReverse.NONE_DECLARED) : Undo.reverseBlocked(context, definition.get());
                if (blocked.isPresent()) {
                    LOG.info("Step {} ran but its reverse cannot run ({}): not undone", stepId, blocked.get());
                    stepsWithoutReverse.add(stepId);
                    progress(stepId);
                    continue;
                }
                try {
                    Undo.reverseStep(context, (InstallStep.RunCommand) definition.get());
                    stepsReversed++;
                } catch (de.yawi.installer.core.error.CancelledException e) {
                    throw e;
                } catch (RuntimeException e) {
                    failure("reverse of step " + stepId, e);
                }
                progress(stepId);
            }
        }

        /** Undoes the non-file changes of each recorded file association (E13-S02); the files go with the rest. */
        private void reverseAssociations(Inventory inventory) {
            if (inventory.associations().isEmpty()) {
                return;
            }
            ExecutionContext context = context(listener, token);
            for (String stepId : inventory.associations()) {
                token.checkpoint();
                Optional<IntegrationConfig.FileAssociation> assoc = InstallStep.FileAssociation.extensionOf(stepId)
                        .flatMap(Uninstaller.this::association);
                if (assoc.isPresent()) {
                    try {
                        Undo.reverseAssociation(context, new InstallStep.FileAssociation(assoc.get()));
                    } catch (RuntimeException e) {
                        failure("reverse of association " + stepId, e);
                    }
                }
                progress(stepId);
            }
        }

        /** Undoes the PATH entries (E13-S03): removes the marked block / registry addition, from the manifest. */
        private void reversePathEntries(Inventory inventory) {
            if (inventory.pathEntries().isEmpty()) {
                return;
            }
            ExecutionContext context = context(listener, token);
            InstallStep.PathEntries step = new InstallStep.PathEntries(manifest.integration().pathEntries());
            for (String stepId : inventory.pathEntries()) {
                token.checkpoint();
                try {
                    Undo.reversePathEntries(context, step);
                } catch (RuntimeException e) {
                    failure("reverse of PATH entries", e);
                }
                progress(stepId);
            }
        }

        private void deleteFiles(Inventory inventory) {
            for (String path : inventory.files()) {
                token.checkpoint();
                if (inventory.modified().contains(path) && !options.deleteModified()) {
                    LOG.info("Keeping {}: changed since the installation", path);
                    keptModified.add(path);
                    progress(path);
                    continue;
                }
                delete(path);
                progress(path);
            }
        }

        /**
         * Looks again first: the reverse commands may have removed files the
         * inventory listed (their own marks) or left new ones.
         */
        private void deleteUnrecorded(Inventory inventory) {
            Set<Path> recordedTrees = new LinkedHashSet<>();
            inventory.files().stream().map(record::resolve)
                    .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).forEach(recordedTrees::add);
            List<String> unrecorded = unrecordedFiles(inventory.files(), recordedTrees);
            if (!options.deleteUnrecorded()) {
                keptUnrecorded.addAll(unrecorded);
                unrecorded.forEach(p -> LOG.info("Keeping {}: not installed by the installer", p));
                return;
            }
            total += unrecorded.size() - inventory.unrecorded().size();
            for (String path : unrecorded) {
                token.checkpoint();
                delete(path);
                progress(path);
            }
        }

        private void delete(String recordedPath) {
            Path path = record.resolve(recordedPath);
            try {
                if (Undo.deletePath(path)) {
                    deleted++;
                    removedFiles.add(path);
                    LOG.debug("Deleted {}", path);
                }
                rememberParent(path);
            } catch (IOException e) {
                failure("delete of " + path, e);
            }
        }

        private void rememberParent(Path path) {
            Path parent = path.getParent();
            while (parent != null && parent.startsWith(destination) && !parent.equals(destination)) {
                parentsToPrune.add(parent);
                parent = parent.getParent();
            }
        }

        private void deleteInstallerDir() {
            Path dir = destination.resolve(RecordWriter.DIRECTORY);
            try {
                if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    Backup.deleteTree(dir);
                    deleted++;
                    LOG.info("Deleted the record and backups under {}", dir);
                }
            } catch (IOException e) {
                failure("delete of " + dir, e);
            }
            progress(RecordWriter.DIRECTORY);
        }

        /** The register is a convenience for the next start; a failure here is a warning, never a failure of the uninstall. */
        private void unregister() {
            removeRegisterEntry();
            progress("register");
        }

        /** Recorded directories innermost first, then the emptied parents of everything deleted - each only if empty. */
        private void deleteDirectories(Inventory inventory) {
            List<String> directories = inventory.directories();
            for (int i = directories.size() - 1; i >= 0; i--) {
                token.checkpoint();
                Path dir = record.resolve(directories.get(i));
                if (deleteIfEmpty(dir, directories.get(i))) {
                    rememberParent(dir);
                }
                parentsToPrune.remove(dir);
                progress(directories.get(i));
            }
            for (Path parent : new ArrayList<>(parentsToPrune)) {
                deleteIfEmpty(parent, record.relativize(parent));
            }
        }

        private boolean deleteIfEmpty(Path dir, String what) {
            try {
                if (Undo.deleteIfEmpty(dir)) {
                    deleted++;
                    return true;
                }
            } catch (IOException e) {
                failure("delete of " + what, e);
            }
            return false;
        }

        private void failure(String what, Exception e) {
            String line = what + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            LOG.error("Uninstall: {}", line, e);
            failures.add(line);
        }

        private void progress(String what) {
            done++;
            listener.uninstallProgress(done, total, what);
        }
    }
}
