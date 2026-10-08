package de.yawi.installer.core.manifest;

import de.yawi.installer.core.platform.OperatingSystem;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Description of one {@code <step>} as written in the manifest.
 *
 * <p>This is data only; the engine turns it into something executable.
 * Nested records on purpose so that can use {@code ExtractStep} & Co. for
 * the executable counterparts without a name clash.
 */
public sealed interface InstallStep {

    String id();

    /** Share of the overall progress bar. */
    int weight();

    /** Manifest value of {@code @type}. */
    String typeName();

    /** What to do when a {@code run-command} step fails. */
    enum OnFailure {
        ABORT, CONTINUE, ASK;

        public static OnFailure fromManifestName(String name) {
            return valueOf(name.toUpperCase(Locale.ROOT));
        }
    }

    /** {@code type="extract"}: unpack a source archive into {@code to}. */
    record Extract(String id, int weight, String archiveRef, String to) implements InstallStep {
        public Extract {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(archiveRef, "archiveRef");
            Objects.requireNonNull(to, "to");
        }

        @Override
        public String typeName() {
            return "extract";
        }
    }

    /**
     * {@code type="template"}: copy {@code from} to {@code to} replacing
     * {@code ${KEY}} with {@code replacements}.
     *
     * @param encoding charset of template and result, default UTF-8
     */
    record Template(String id, int weight, String from, String to, Map<String, String> replacements,
                    String encoding) implements InstallStep {
        public Template {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            // Manifest order matters for the dry run and the log; Map.copyOf would lose it.
            replacements = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(replacements));
            encoding = encoding == null || encoding.isBlank() ? "UTF-8" : encoding;
        }

        @Override
        public String typeName() {
            return "template";
        }
    }

    /** {@code type="copy"}: copy a file or directory. */
    record Copy(String id, int weight, String from, String to) implements InstallStep {
        public Copy {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }

        @Override
        public String typeName() {
            return "copy";
        }
    }

    /** {@code type="mkdir"}: create a directory (and its parents). */
    record Mkdir(String id, int weight, String to) implements InstallStep {
        public Mkdir {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(to, "to");
        }

        @Override
        public String typeName() {
            return "mkdir";
        }
    }

    /**
     * {@code type="chmod"}: set POSIX permissions; a no-op on Windows.
     *
     * @param mode octal, e.g. {@code 755}
     */
    record Chmod(String id, int weight, String to, String mode) implements InstallStep {
        public Chmod {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(mode, "mode");
        }

        @Override
        public String typeName() {
            return "chmod";
        }
    }

    /**
     * {@code type="run-command"}: run one or more processes, chosen by OS.
     *
     * @param timeoutSeconds {@code 0} means no timeout
     * @param workingDir     may be null
     * @param commands       commands per OS; an OS without entry has nothing to run
     * @param rollback       optional reverse commands per OS
     */
    record RunCommand(String id, int weight, int expectExitCode, int timeoutSeconds, OnFailure onFailure,
                      boolean elevated, String workingDir, Map<String, String> env,
                      Map<OperatingSystem, List<Command>> commands,
                      Map<OperatingSystem, List<Command>> rollback) implements InstallStep {
        public RunCommand {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(onFailure, "onFailure");
            env = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(env));
            commands = copyCommands(commands);
            rollback = copyCommands(rollback);
        }

        private static Map<OperatingSystem, List<Command>> copyCommands(Map<OperatingSystem, List<Command>> source) {
            Map<OperatingSystem, List<Command>> copy = new java.util.EnumMap<>(OperatingSystem.class);
            source.forEach((os, list) -> copy.put(os, List.copyOf(list)));
            return java.util.Collections.unmodifiableMap(copy);
        }

        @Override
        public String typeName() {
            return "run-command";
        }

        public Optional<String> workingDirOrEmpty() {
            return Optional.ofNullable(workingDir);
        }

        public List<Command> commandsFor(OperatingSystem os) {
            return commands.getOrDefault(os, List.of());
        }

        public List<Command> rollbackFor(OperatingSystem os) {
            return rollback.getOrDefault(os, List.of());
        }
    }

    /**
     * A {@code <shortcut>} of the {@code <integration>} block, run as an
     * implicit step after the component steps. Not a manifest
     * {@code <step>}: the id is {@code shortcut:<shortcut id>}, the weight a
     * fixed 1, and the reader never creates one.
     */
    record Shortcut(IntegrationConfig.Shortcut shortcut) implements InstallStep {
        public static final String ID_PREFIX = "shortcut:";

        public Shortcut {
            Objects.requireNonNull(shortcut, "shortcut");
        }

        @Override
        public String id() {
            return ID_PREFIX + shortcut.id();
        }

        @Override
        public int weight() {
            return 1;
        }

        @Override
        public String typeName() {
            return "shortcut";
        }

        /** The shortcut id behind a step id of this kind, if it is one. */
        public static Optional<String> shortcutIdOf(String stepId) {
            return stepId != null && stepId.startsWith(ID_PREFIX)
                    ? Optional.of(stepId.substring(ID_PREFIX.length())) : Optional.empty();
        }
    }

    /**
     * A {@code <fileAssociation>} of the {@code <integration>} block, run as an
     * implicit step after the shortcuts. Not a manifest
     * {@code <step>}: the id is {@code association:<extension>}, the weight a
     * fixed 1, and the reader never creates one.
     */
    record FileAssociation(IntegrationConfig.FileAssociation association) implements InstallStep {
        public static final String ID_PREFIX = "association:";

        public FileAssociation {
            Objects.requireNonNull(association, "association");
        }

        @Override
        public String id() {
            return ID_PREFIX + association.extension();
        }

        @Override
        public int weight() {
            return 1;
        }

        @Override
        public String typeName() {
            return "fileAssociation";
        }

        /** The extension behind a step id of this kind, if it is one. */
        public static Optional<String> extensionOf(String stepId) {
            return stepId != null && stepId.startsWith(ID_PREFIX)
                    ? Optional.of(stepId.substring(ID_PREFIX.length())) : Optional.empty();
        }
    }

    /**
     * The {@code <pathEntry>} elements of the {@code <integration>} block, run
     * as one implicit step after the file associations . Not a manifest
     * {@code <step>}: all entries share the single fixed id {@code path:entries}
     * (one marked block per profile), the weight a fixed 1, and the reader never
     * creates one.
     */
    record PathEntries(List<String> entries) implements InstallStep {
        public static final String STEP_ID = "path:entries";

        public PathEntries {
            entries = List.copyOf(entries);
        }

        @Override
        public String id() {
            return STEP_ID;
        }

        @Override
        public int weight() {
            return 1;
        }

        @Override
        public String typeName() {
            return "pathEntry";
        }

        /** Whether {@code stepId} is the PATH step's id. */
        public static boolean isPathStep(String stepId) {
            return STEP_ID.equals(stepId);
        }
    }
}
