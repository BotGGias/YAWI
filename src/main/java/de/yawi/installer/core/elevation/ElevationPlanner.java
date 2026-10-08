package de.yawi.installer.core.elevation;

import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.ExecutionPlan;
import de.yawi.installer.core.engine.InstallRunner;
import de.yawi.installer.core.engine.PlannedStep;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.state.InstallationRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what of a plan needs administrator rights: the
 * destination (then everything runs elevated) or single
 * {@code run-command} steps marked {@code elevated="true"} (then only those,
 * as one block after the rest). A process that already has the rights needs
 * nothing.
 */
public final class ElevationPlanner {

    /** What a secret input shows as in the clear-text command list. */
    public static final String MASK = "••••";

    private ElevationPlanner() {
    }

    /** Convenience over the pure overload with the platform's own answers. */
    public static ElevationNeed assess(ExecutionPlan plan, Path destination, Platform platform) {
        return assess(plan, platform.requiresElevation(destination), platform.isElevated());
    }

    /**
     * @param destinationRequires {@link Platform#requiresElevation} for the destination
     * @param alreadyElevated     {@link Platform#isElevated()}
     */
    public static ElevationNeed assess(ExecutionPlan plan, boolean destinationRequires, boolean alreadyElevated) {
        if (alreadyElevated) {
            return ElevationNeed.NONE;
        }
        if (destinationRequires) {
            return new ElevationNeed(ElevationNeed.Mode.WHOLE, plan.ids(), true);
        }
        List<String> elevated = new ArrayList<>();
        for (PlannedStep planned : plan.steps()) {
            if (!planned.isSkipped() && planned.definition() instanceof InstallStep.RunCommand run && run.elevated()) {
                elevated.add(planned.id());
            }
        }
        return elevated.isEmpty() ? ElevationNeed.NONE
                : new ElevationNeed(ElevationNeed.Mode.PARTIAL, elevated, false);
    }

    /**
     * The commands the elevated process would run, one line per command step
     * ({@code id: run ...}), resolved against {@code context} - build it with
     * {@link #maskSecrets} so no password shows (E12-S01-T03, E10-S03).
     * File steps of a whole elevated run are not listed; they only touch the
     * destination.
     */
    public static List<String> describeCommands(ExecutionPlan plan, Collection<String> ids, ExecutionContext context) {
        Set<String> wanted = Set.copyOf(ids);
        List<String> lines = new ArrayList<>();
        for (PlannedStep planned : plan.steps()) {
            if (wanted.contains(planned.id()) && !planned.isSkipped()
                    && planned.definition() instanceof InstallStep.RunCommand) {
                lines.add(planned.id() + ": " + planned.step().describe(context));
            }
        }
        return lines;
    }

    /** The inputs with every secret value replaced by {@link #MASK}. */
    public static Map<String, String> maskSecrets(InstallManifest manifest, Map<String, String> inputs) {
        Map<String, String> safe = InstallationRecord.withoutSecrets(inputs, InstallRunner.inputLabels(manifest));
        Map<String, String> masked = new LinkedHashMap<>();
        inputs.forEach((id, value) -> masked.put(id, safe.containsKey(id) ? value : MASK));
        return masked;
    }
}
