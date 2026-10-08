package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.state.InstallationRecord;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything a step needs while it runs. Built by the UI from the
 * {@code InstallerModel} and by the silent mode from its arguments.
 *
 * @param inputs             values of the inputs of the selected components only
 * @param selectedComponents the resolved selection (dependencies included)
 */
public record ExecutionContext(InstallManifest manifest, Platform platform, Path destination,
                               Map<String, String> inputs, Set<String> selectedComponents,
                               Placeholders placeholders, Artifacts artifacts, InstallationRecord record,
                               ProgressListener listener, FailurePrompt failurePrompt,
                               CancellationToken cancellation) {

    /** Name of the installer's scratch directory below the platform's temp directory. */
    public static final String WORK_DIR_NAME = "yawi-installer";

    public ExecutionContext {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(destination, "destination");
        inputs = Map.copyOf(inputs);
        selectedComponents = Set.copyOf(selectedComponents);
        Objects.requireNonNull(placeholders, "placeholders");
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(record, "record");
        listener = listener == null ? ProgressListener.NOOP : listener;
        failurePrompt = failurePrompt == null ? FailurePrompt.ABORT_ALWAYS : failurePrompt;
        Objects.requireNonNull(cancellation, "cancellation");
    }

    /** The installer's scratch directory: what {@code ${tempDir}} resolves to and the second root of the path guard. */
    public static Path workDir(Platform platform) {
        return platform.tempDir().resolve(WORK_DIR_NAME);
    }

    public Path workDir() {
        return workDir(platform);
    }

    /**
     * The same context for the rollback: its own listener and a fresh
     * cancellation token, because the one of the run is cancelled by then and
     * the reverse operations must not stop at the first checkpoint.
     */
    public ExecutionContext forRollback(ProgressListener listener, CancellationToken cancellation) {
        return new ExecutionContext(manifest, platform, destination, inputs, selectedComponents, placeholders,
                artifacts, record, listener, FailurePrompt.ABORT_ALWAYS, cancellation);
    }

    /** Shorthand for {@code placeholders().resolve(text)}. */
    public String resolve(String text) {
        return placeholders.resolve(text);
    }
}
