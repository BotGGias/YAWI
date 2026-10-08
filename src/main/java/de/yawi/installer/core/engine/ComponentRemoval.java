package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRecord.CreatedDirectory;
import de.yawi.installer.core.state.InstallationRecord.CreatedFile;
import de.yawi.installer.core.state.InstallationRecord.Entry;
import de.yawi.installer.core.state.InstallationRecord.StepFinished;
import de.yawi.installer.core.state.InstallationRecord.StepStarted;
import de.yawi.installer.core.state.RecordSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
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
 * Takes the steps of deselected components back when a "modify" changes the
 * selection: the newly chosen components are (re)installed by the
 * ordinary {@link InstallRunner} run, and afterwards this removes exactly what
 * the components the user dropped had put there. It reads the <em>previous</em>
 * record (the run rewrites the record for the new selection), attributes files
 * to steps by their {@link StepStarted}/{@link StepFinished} brackets, runs the
 * {@code <rollback>} of any removed {@code run-command}, deletes the removed
 * files (newest first) and prunes the directories that end up empty. Shares
 * {@link Undo} with {@link Rollback} and {@link Uninstaller}.
 *
 * <p>Best effort: a single failure is collected and reported, the rest goes on;
 * it never fails the modify. Only declared {@code <step>}s are considered - the
 * implicit integration steps (shortcuts, associations, PATH) are not owned by a
 * component and are re-run by the install.
 */
public final class ComponentRemoval {

    private static final Logger LOG = LoggerFactory.getLogger(ComponentRemoval.class);

    /**
     * @param removed             how many files, trees and directories were deleted
     * @param stepsReversed       {@code run-command} steps whose reverse commands ran
     * @param stepsWithoutReverse steps that ran but could not be reversed
     * @param failures            one line per item that could not be removed
     */
    public record Report(int removed, int stepsReversed, List<String> stepsWithoutReverse, List<String> failures) {
        public Report {
            stepsWithoutReverse = List.copyOf(stepsWithoutReverse);
            failures = List.copyOf(failures);
        }

        public boolean clean() {
            return failures.isEmpty();
        }
    }

    private final InstallManifest manifest;
    private final Platform platform;
    private final InstallationRecord previousRecord;

    public ComponentRemoval(InstallManifest manifest, Platform platform, InstallationRecord previousRecord) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.previousRecord = Objects.requireNonNull(previousRecord, "previousRecord");
    }

    /**
     * The declared step ids that the old selection ran but the new one no longer
     * needs. Both selections are resolved (dependencies included) first, so a
     * step a kept component still uses is never removed.
     */
    public static Set<String> removedStepIds(InstallManifest manifest, Platform platform, Set<String> oldResolved,
                                             Set<String> newResolved) {
        Set<String> kept = new LinkedHashSet<>();
        manifest.stepsFor(newResolved, platform.os()).forEach(s -> kept.add(s.id()));
        Set<String> removed = new LinkedHashSet<>();
        manifest.stepsFor(oldResolved, platform.os()).forEach(s -> {
            if (!kept.contains(s.id())) {
                removed.add(s.id());
            }
        });
        return removed;
    }

    /** Reverses the given steps from the previous record. Never throws for a failed item. */
    public Report run(Set<String> removedStepIds, ProgressListener listener, CancellationToken token) {
        ProgressListener out = listener == null ? ProgressListener.NOOP : listener;
        CancellationToken cancellation = token == null ? new CancellationToken() : token;
        if (removedStepIds.isEmpty()) {
            return new Report(0, 0, List.of(), List.of());
        }

        // Attribute recorded files/directories to their step via the StepStarted/StepFinished brackets.
        List<String> removedFiles = new ArrayList<>();
        List<String> removedDirs = new ArrayList<>();
        String current = null;
        for (Entry entry : previousRecord.entries()) {
            switch (entry) {
                case StepStarted s -> current = s.stepId();
                case StepFinished f -> current = null;
                case CreatedFile cf -> {
                    if (current != null && removedStepIds.contains(current)) {
                        removedFiles.add(cf.path());
                    }
                }
                case CreatedDirectory cd -> {
                    if (current != null && removedStepIds.contains(current)) {
                        removedDirs.add(cd.path());
                    }
                }
                default -> {
                }
            }
        }

        List<String> failures = new ArrayList<>();
        List<String> withoutReverse = new ArrayList<>();
        int[] counters = {0, 0}; // removed, stepsReversed
        Set<Path> parents = new TreeSet<>(Comparator.comparingInt(Path::getNameCount).reversed()
                .thenComparing(Comparator.naturalOrder()));
        ExecutionContext context = context(out, cancellation);

        // 1. The reverse commands of removed run-command steps, while their files are still there.
        for (String stepId : removedStepIds) {
            cancellation.checkpoint();
            Optional<InstallStep> definition = manifest.step(stepId);
            if (definition.isEmpty() || !(definition.get() instanceof InstallStep.RunCommand runCommand)) {
                continue; // a file step has nothing to reverse; its files go below
            }
            Optional<Undo.NoReverse> blocked = Undo.reverseBlocked(context, runCommand);
            if (blocked.isPresent()) {
                LOG.info("Step {} ran but its reverse cannot run ({}): not undone", stepId, blocked.get());
                withoutReverse.add(stepId);
                continue;
            }
            try {
                Undo.reverseStep(context, runCommand);
                counters[1]++;
            } catch (RuntimeException e) {
                failures.add("reverse of step " + stepId + ": " + message(e));
            }
        }

        // 2. The removed files, newest first.
        List<Path> removedPaths = new ArrayList<>();
        for (int i = removedFiles.size() - 1; i >= 0; i--) {
            cancellation.checkpoint();
            String recorded = removedFiles.get(i);
            Path path = previousRecord.resolve(recorded);
            try {
                if (Undo.deletePath(path)) {
                    counters[0]++;
                    removedPaths.add(path);
                    out.output("removed " + recorded);
                }
                rememberParent(parents, path);
            } catch (IOException e) {
                failures.add("delete of " + path + ": " + message(e));
            }
        }
        Undo.refreshMenus(platform, removedPaths);
        Undo.refreshMimeDatabase(platform, removedPaths);

        // 3. The removed directories, innermost first, then the emptied parents - each only if empty.
        for (int i = removedDirs.size() - 1; i >= 0; i--) {
            cancellation.checkpoint();
            Path dir = previousRecord.resolve(removedDirs.get(i));
            if (deleteIfEmpty(dir, failures)) {
                counters[0]++;
                rememberParent(parents, dir);
            }
            parents.remove(dir);
        }
        for (Path parent : new ArrayList<>(parents)) {
            if (deleteIfEmpty(parent, failures)) {
                counters[0]++;
            }
        }

        Report report = new Report(counters[0], counters[1], withoutReverse, failures);
        LOG.info("Component removal: {} deleted, {} step(s) reversed, {} without reverse, {} failure(s)",
                report.removed(), report.stepsReversed(), withoutReverse.size(), failures.size());
        return report;
    }

    private boolean deleteIfEmpty(Path dir, List<String> failures) {
        try {
            return Undo.deleteIfEmpty(dir);
        } catch (IOException e) {
            failures.add("delete of " + dir + ": " + message(e));
            return false;
        }
    }

    private void rememberParent(Set<Path> parents, Path path) {
        Path destination = previousRecord.destination();
        Path parent = path.getParent();
        while (parent != null && parent.startsWith(destination) && !parent.equals(destination)) {
            parents.add(parent);
            parent = parent.getParent();
        }
    }

    /** A context for the reverse commands: placeholders from the previous record's selection and inputs. */
    private ExecutionContext context(ProgressListener listener, CancellationToken token) {
        RecordSelection selection = RecordSelection.from(manifest, previousRecord);
        Set<String> selected = manifest.resolveSelection(selection.components());
        Map<String, String> inputs = InstallRunner.selectedInputs(manifest, selected, selection.inputs());
        Placeholders placeholders = Placeholders.of(manifest, platform, previousRecord.destination(), inputs, selected);
        return new ExecutionContext(manifest, platform, previousRecord.destination(), inputs, selected, placeholders,
                new BundledArtifacts(), previousRecord, listener, FailurePrompt.ABORT_ALWAYS, token);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
