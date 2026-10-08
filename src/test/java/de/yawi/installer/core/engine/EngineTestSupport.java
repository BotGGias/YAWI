package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.platform.Architecture;
import de.yawi.installer.core.platform.Environment;
import de.yawi.installer.core.platform.LinuxPlatform;
import de.yawi.installer.core.platform.MacPlatform;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.WindowsPlatform;
import de.yawi.installer.core.state.InstallationRecord;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Fakes and builders shared by the engine tests. */
final class EngineTestSupport {

    private EngineTestSupport() {
    }

    static Platform linux() {
        return new LinuxPlatform(Architecture.X64, Environment.of(Map.of("HOME", "/home/me"),
                Map.of("user.home", "/home/me", "java.io.tmpdir", "/tmp")));
    }

    static Platform windows() {
        return new WindowsPlatform(Architecture.X64, Environment.of(
                Map.of("LOCALAPPDATA", "C:\\Users\\me\\AppData\\Local", "USERPROFILE", "C:\\Users\\me"),
                Map.of("user.home", "C:\\Users\\me", "java.io.tmpdir", "C:\\Temp")));
    }

    static Platform mac() {
        return new MacPlatform(Architecture.AARCH64, Environment.of(Map.of("HOME", "/Users/me"),
                Map.of("user.home", "/Users/me", "java.io.tmpdir", "/tmp")));
    }

    static InstallationRecord record(Path destination) {
        return new InstallationRecord("test", "1", Instant.EPOCH, destination, Set.of(), Map.of());
    }

    static ExecutionContext context(InstallManifest manifest, Platform platform, Path destination,
                                    Map<String, String> inputs, Set<String> selected,
                                    ProgressListener listener, FailurePrompt prompt, CancellationToken token) {
        Set<String> resolved = manifest.resolveSelection(selected);
        return new ExecutionContext(manifest, platform, destination, inputs, resolved,
                Placeholders.of(manifest, platform, destination, inputs, resolved),
                new BundledArtifacts(), record(destination), listener, prompt, token);
    }

    /** A step whose execution is a lambda; {@code describe} names the id. */
    static ExecutableStep step(InstallStep definition, Consumer<ExecutionContext> body) {
        return new ExecutableStep() {
            @Override
            public InstallStep definition() {
                return definition;
            }

            @Override
            public void execute(ExecutionContext context) {
                body.accept(context);
            }

            @Override
            public String describe(ExecutionContext context) {
                return "-> " + definition.id();
            }
        };
    }

    /** A factory whose steps do nothing but record their id in {@code ran}. */
    static StepFactory recording(java.util.List<String> ran) {
        return definition -> step(definition, ctx -> ran.add(definition.id()));
    }
}
