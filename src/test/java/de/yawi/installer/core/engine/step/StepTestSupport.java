package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.Artifacts;
import de.yawi.installer.core.engine.BundledArtifacts;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.FailurePrompt;
import de.yawi.installer.core.engine.Placeholders;
import de.yawi.installer.core.engine.ProgressListener;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.state.InstallationRecord;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Execution contexts against the real platform and a temp destination. */
final class StepTestSupport {

    private StepTestSupport() {
    }

    /** A listener that keeps every output line and progress message. */
    static class Capture implements ProgressListener {
        final List<String> output = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final List<Double> fractions = new ArrayList<>();

        @Override
        public void stepProgress(double fraction, String message) {
            fractions.add(fraction);
            messages.add(message);
        }

        @Override
        public void output(String line) {
            output.add(line);
        }
    }

    static ExecutionContext context(Path destination, Map<String, String> inputs, Capture capture) {
        return context(TestManifests.parse("/manifest/engine/file-steps.xml"), destination, inputs, capture,
                new BundledArtifacts(), new CancellationToken());
    }

    static ExecutionContext context(InstallManifest manifest, Path destination, Map<String, String> inputs,
                                    ProgressListener listener, Artifacts artifacts, CancellationToken token) {
        var platform = PlatformFactory.current();
        Set<String> selected = manifest.resolveSelection(Set.of("core"));
        InstallationRecord record = new InstallationRecord(manifest.product().id(), manifest.product().version(),
                Instant.EPOCH, destination, selected, inputs);
        return new ExecutionContext(manifest, platform, destination, inputs, selected,
                Placeholders.of(manifest, platform, destination, inputs, selected),
                artifacts, record, listener, FailurePrompt.ABORT_ALWAYS, token);
    }
}
