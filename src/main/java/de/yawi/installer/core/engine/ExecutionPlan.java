package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.ManifestException;
import de.yawi.installer.core.manifest.ManifestProblem;
import de.yawi.installer.core.platform.Platform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The steps of one installation in execution order, computed before anything
 * runs: the selection's steps (each once, {@link InstallManifest#stepsFor}),
 * steps without commands for this platform marked as skipped, weights
 * normalised to a sum of 1. Testable without executing anything through
 * {@link #dryRun}.
 */
public final class ExecutionPlan {

    private final List<PlannedStep> steps;

    private ExecutionPlan(List<PlannedStep> steps) {
        this.steps = List.copyOf(steps);
    }

    /**
     * @param selection the resolved component selection
     * @throws ManifestException if a step uses a placeholder that cannot be
     *         resolved for this selection (e.g. the input of a deselected
     *         component), or if the manifest is not trusted and a selected
     *         step would run commands (E10-S03-T03)
     */
    public static ExecutionPlan build(InstallManifest manifest, Collection<String> selection, Platform platform,
                                      Placeholders placeholders, StepFactory factory) {
        return build(manifest, selection, platform, placeholders, factory,
                manifest.integration().fileAssociations().stream().map(a -> a.extension()).toList(), true);
    }

    /**
     * As {@link #build(InstallManifest, Collection, Platform, Placeholders, StepFactory)}, but only the
     * {@code <fileAssociation>}s whose extension is in {@code associationExtensions} are created (E13-S02:
     * the user can turn the associations off) and {@code createPathEntries} decides whether the
     * {@code <pathEntry>}s are added (E13-S03). The five-argument overload creates every declared one, so
     * the elevated child - which rebuilds the full plan and then keeps only the parent's step ids - stays
     * in step with the parent's choice.
     */
    public static ExecutionPlan build(InstallManifest manifest, Collection<String> selection, Platform platform,
                                      Placeholders placeholders, StepFactory factory,
                                      Collection<String> associationExtensions) {
        return build(manifest, selection, platform, placeholders, factory, associationExtensions, true);
    }

    /** @see #build(InstallManifest, Collection, Platform, Placeholders, StepFactory, Collection) */
    public static ExecutionPlan build(InstallManifest manifest, Collection<String> selection, Platform platform,
                                      Placeholders placeholders, StepFactory factory,
                                      Collection<String> associationExtensions, boolean createPathEntries) {
        Set<String> selected = manifest.resolveSelection(selection);
        List<InstallStep> definitions = new ArrayList<>(manifest.stepsFor(selected, platform.os()));
        // The <integration> shortcuts, file associations and PATH entries run last, as implicit steps (E13);
        // no <step> declares them.
        manifest.integration().shortcuts().forEach(s -> definitions.add(new InstallStep.Shortcut(s)));
        Set<String> enabledExtensions = Set.copyOf(associationExtensions);
        manifest.integration().fileAssociations().stream()
                .filter(a -> enabledExtensions.contains(a.extension()))
                .forEach(a -> definitions.add(new InstallStep.FileAssociation(a)));
        List<String> pathEntries = manifest.integration().pathEntries();
        if (createPathEntries && !pathEntries.isEmpty()) {
            definitions.add(new InstallStep.PathEntries(pathEntries));
        }

        List<ManifestProblem> problems = new ArrayList<>();
        // Commands from an unsigned manifest off the network never run, whatever the build's policy let through.
        if (!manifest.origin().isTrusted()) {
            definitions.stream()
                    .filter(InstallStep.RunCommand.class::isInstance)
                    .forEach(d -> problems.add(ManifestProblem.error("step '" + d.id() + "'",
                            "runs commands, but the manifest was loaded from the network without a valid signature")));
            if (!problems.isEmpty()) {
                throw new ManifestException("Manifest " + manifest.origin().location()
                        + " is not signed; its command steps are refused", problems);
            }
        }
        for (InstallStep definition : definitions) {
            String subject = "step '" + definition.id() + "'";
            for (String text : StepTexts.of(definition, platform)) {
                problems.addAll(placeholders.validate(subject, text));
            }
        }
        if (!problems.isEmpty()) {
            throw new ManifestException("Steps use placeholders that cannot be resolved", problems);
        }

        // XSD default weight is 1, so a manifest without weights spreads evenly.
        double total = definitions.stream().mapToDouble(d -> Math.max(d.weight(), 0)).sum();
        List<PlannedStep> planned = new ArrayList<>();
        for (InstallStep definition : definitions) {
            double weight = total <= 0 ? 1.0 / definitions.size() : Math.max(definition.weight(), 0) / total;
            planned.add(new PlannedStep(factory.create(definition), weight, skipReason(definition, platform)));
        }
        return new ExecutionPlan(planned);
    }

    private static Optional<String> skipReason(InstallStep definition, Platform platform) {
        if (definition instanceof InstallStep.RunCommand run && run.commandsFor(platform.os()).isEmpty()) {
            return Optional.of("no commands for " + platform.os().manifestName().orElse(platform.os().name()));
        }
        return Optional.empty();
    }

    public List<PlannedStep> steps() {
        return steps;
    }

    /**
     * The steps whose ids are in {@code ids}, in this plan's order, with the
     * weights normalised again to a sum of 1 (E12-S03: a segment runs through
     * its own {@link Engine}, which reports its progress from 0 to 1; the
     * caller scales it back into the whole). Unknown ids are ignored.
     */
    public ExecutionPlan subset(Collection<String> ids) {
        Set<String> wanted = Set.copyOf(ids);
        List<PlannedStep> chosen = steps.stream().filter(p -> wanted.contains(p.id())).toList();
        double total = chosen.stream().mapToDouble(PlannedStep::weight).sum();
        List<PlannedStep> planned = new ArrayList<>();
        for (PlannedStep p : chosen) {
            double weight = total <= 0 ? 1.0 / chosen.size() : p.weight() / total;
            planned.add(new PlannedStep(p.step(), weight, p.skipReason()));
        }
        return new ExecutionPlan(planned);
    }

    /** The ids of all steps, in order. */
    public List<String> ids() {
        return steps.stream().map(PlannedStep::id).toList();
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** One line per step, numbered, with the resolved description or the skip reason. */
    public List<String> dryRun(ExecutionContext context) {
        List<String> lines = new ArrayList<>();
        int n = 1;
        for (PlannedStep planned : steps) {
            InstallStep definition = planned.definition();
            String line = n++ + ". [" + definition.typeName() + "] " + definition.id()
                    + " (" + Math.round(planned.weight() * 100) + " %)";
            lines.add(planned.skipReason()
                    .map(reason -> line + " - skipped: " + reason)
                    .orElseGet(() -> line + " " + planned.step().describe(context)));
        }
        return lines;
    }

    /** The manifest texts of a step that may carry placeholders, for validation. */
    static final class StepTexts {
        private StepTexts() {
        }

        static List<String> of(InstallStep definition, Platform platform) {
            List<String> texts = new ArrayList<>();
            switch (definition) {
                case InstallStep.Extract s -> texts.add(s.to());
                case InstallStep.Copy s -> {
                    texts.add(s.from());
                    texts.add(s.to());
                }
                case InstallStep.Template s -> {
                    texts.add(s.from());
                    texts.add(s.to());
                    texts.addAll(s.replacements().values());
                }
                case InstallStep.Mkdir s -> texts.add(s.to());
                case InstallStep.Chmod s -> texts.add(s.to());
                case InstallStep.RunCommand s -> {
                    s.workingDirOrEmpty().ifPresent(texts::add);
                    texts.addAll(s.env().values());
                    // Only this platform's commands run, so only they are checked.
                    s.commandsFor(platform.os()).forEach(c -> texts.addAll(c.args()));
                }
                case InstallStep.Shortcut s -> {
                    texts.add(s.shortcut().name());
                    texts.add(s.shortcut().target());
                    s.shortcut().iconOrEmpty().ifPresent(texts::add);
                }
                case InstallStep.FileAssociation s -> {
                    texts.add(s.association().target());
                    s.association().descriptionOrEmpty().ifPresent(texts::add);
                }
                case InstallStep.PathEntries s -> texts.addAll(s.entries());
            }
            return texts;
        }
    }
}
