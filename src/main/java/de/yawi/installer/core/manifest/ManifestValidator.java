package de.yawi.installer.core.manifest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Business rules the schema cannot express.
 *
 * <p>Every rule is one method, every finding names the affected id, and all
 * findings are collected so the packager can fix them in one go. The rules
 * are listed in manifest-schema.md under "Was das XSD nicht abdeckt".
 */
public final class ManifestValidator {

    /** Schema versions this installer understands. */
    public static final Set<Integer> SUPPORTED_SCHEMA_VERSIONS = Set.of(1);

    public List<ManifestProblem> validate(InstallManifest manifest) {
        List<ManifestProblem> problems = new ArrayList<>();
        checkSchemaVersion(manifest, problems);
        checkDependencyCycles(manifest, problems);
        checkSomethingIsInstalled(manifest, problems);
        checkEverySourceHasAProvider(manifest, problems);
        checkPageNames(manifest, problems);
        checkInputs(manifest, problems);
        warnAboutUnverifiedDownloads(manifest, problems);
        warnAboutUntrustedCommands(manifest, problems);
        return problems;
    }

    /** the plan refuses command steps of an unsigned network manifest; say so at load time. */
    private static void warnAboutUntrustedCommands(InstallManifest m, List<ManifestProblem> problems) {
        if (m.origin().isTrusted()) {
            return;
        }
        m.steps().stream()
                .filter(InstallStep.RunCommand.class::isInstance)
                .forEach(step -> problems.add(ManifestProblem.warning("step '" + step.id() + "'",
                        "runs commands, but the manifest is not signed - the step will be refused")));
    }

    /**
     * Inputs: a choice needs options and a default among them, bounds must
     * be sane. Global uniqueness of ids ({@code ${input.<id>}}) is already
     * the XSD's {@code inputKey} constraint.
     */
    private static void checkInputs(InstallManifest m, List<ManifestProblem> problems) {
        for (Component c : m.components()) {
            for (ComponentInput input : c.inputs()) {
                String subject = "input '" + input.id() + "' of component '" + c.id() + "'";
                boolean choice = input.type() == ComponentInput.InputType.CHOICE;
                if (choice && input.options().isEmpty()) {
                    problems.add(ManifestProblem.error(subject, "type=\"choice\" needs at least one <option>"));
                }
                if (!choice && !input.options().isEmpty()) {
                    problems.add(ManifestProblem.warning(subject, "<option> elements are ignored for type=\""
                            + input.type().name().toLowerCase(java.util.Locale.ROOT) + "\""));
                }
                if (choice && input.defaultValue() != null && input.option(input.defaultValue()).isEmpty()) {
                    problems.add(ManifestProblem.error(subject, "default \"" + input.defaultValue()
                            + "\" is not one of the options"));
                }
                if (input.min() != null && input.max() != null && input.min() > input.max()) {
                    problems.add(ManifestProblem.error(subject, "min " + input.min() + " is greater than max "
                            + input.max()));
                }
                if (input.type() != ComponentInput.InputType.INT && (input.min() != null || input.max() != null)) {
                    problems.add(ManifestProblem.warning(subject, "min/max only apply to type=\"int\""));
                }
            }
        }
    }

    private static void checkSchemaVersion(InstallManifest m, List<ManifestProblem> problems) {
        if (!SUPPORTED_SCHEMA_VERSIONS.contains(m.schemaVersion())) {
            problems.add(ManifestProblem.error("installer",
                    "schema version " + m.schemaVersion() + " is not supported by this installer (supported: "
                            + SUPPORTED_SCHEMA_VERSIONS + ")"));
        }
    }

    /**
     * Depth-first search over {@code dependsOn}; each cycle is reported once
     * with the full path, e.g. {@code server -> core -> server}.
     */
    private static void checkDependencyCycles(InstallManifest m, List<ManifestProblem> problems) {
        Set<String> done = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (Component start : m.components()) {
            if (done.contains(start.id())) {
                continue;
            }
            Deque<String> path = new ArrayDeque<>();
            Set<String> onPath = new LinkedHashSet<>();
            walk(m, start.id(), path, onPath, done, reported, problems);
        }
    }

    private static void walk(InstallManifest m, String id, Deque<String> path, Set<String> onPath,
                             Set<String> done, Set<String> reported, List<ManifestProblem> problems) {
        if (onPath.contains(id)) {
            List<String> cycle = new ArrayList<>();
            boolean inCycle = false;
            for (String node : onPath) {
                if (node.equals(id)) {
                    inCycle = true;
                }
                if (inCycle) {
                    cycle.add(node);
                }
            }
            cycle.add(id);
            // report the cycle once, at its smallest member, whatever the entry point
            String key = String.valueOf(new java.util.TreeSet<>(cycle));
            if (reported.add(key)) {
                problems.add(ManifestProblem.error("component '" + id + "'",
                        "dependsOn cycle: " + String.join(" -> ", cycle)));
            }
            return;
        }
        if (done.contains(id)) {
            return;
        }
        onPath.add(id);
        path.push(id);
        for (String next : m.component(id).map(Component::dependsOn).orElse(List.of())) {
            walk(m, next, path, onPath, done, reported, problems);
        }
        path.pop();
        onPath.remove(id);
        done.add(id);
    }

    private static void checkSomethingIsInstalled(InstallManifest m, List<ManifestProblem> problems) {
        boolean anyRequired = m.components().stream().anyMatch(Component::required);
        boolean typicalNonEmpty = m.preset(Preset.TYPICAL)
                .map(p -> !p.componentRefs().isEmpty())
                .orElse(false);
        if (!anyRequired && !typicalNonEmpty) {
            problems.add(ManifestProblem.error("components",
                    "no component is required=\"true\" and preset '" + Preset.TYPICAL
                            + "' is missing or empty - a default installation would install nothing"));
        }
    }

    private static void checkEverySourceHasAProvider(InstallManifest m, List<ManifestProblem> problems) {
        for (Source source : m.sources()) {
            if (source.providers().isEmpty()) {
                problems.add(ManifestProblem.error("source '" + source.id() + "'",
                        "has no <provider>; add bundled, http or torrent"));
            }
        }
    }

    private static void checkPageNames(InstallManifest m, List<ManifestProblem> problems) {
        for (String page : m.wizard().pages()) {
            if (WizardConfig.IMPLICIT_PAGES.contains(page)) {
                problems.add(ManifestProblem.error("page '" + page + "'",
                        "reserved: the wizard shows it by itself before the first page when the product is "
                                + "already installed"));
            } else if (!WizardConfig.KNOWN_PAGES.contains(page)) {
                problems.add(ManifestProblem.error("page '" + page + "'",
                        "unknown wizard page; known pages: " + String.join(", ",
                                new java.util.TreeSet<>(WizardConfig.KNOWN_PAGES))));
            }
        }
    }

    /** E10-S01 verifies downloads against {@code sha256}; without it a download cannot be checked. */
    private static void warnAboutUnverifiedDownloads(InstallManifest m, List<ManifestProblem> problems) {
        for (Source source : m.sources()) {
            if (source.sha256() == null && source.hasProviderOfType(Provider.Http.class)) {
                problems.add(ManifestProblem.warning("source '" + source.id() + "'",
                        "has an http provider but no sha256 - the download cannot be verified"));
            }
            // E07-S02: HTTPS is preferred; plain HTTP is neither private nor tamper-proof.
            for (Provider provider : source.providers()) {
                if (provider instanceof Provider.Http http) {
                    http.urls().stream()
                            .filter(u -> "http".equalsIgnoreCase(u.getScheme()))
                            .forEach(u -> problems.add(ManifestProblem.warning("source '" + source.id() + "'",
                                    "downloads over plain http (insecure): " + u)));
                }
            }
        }
    }
}
