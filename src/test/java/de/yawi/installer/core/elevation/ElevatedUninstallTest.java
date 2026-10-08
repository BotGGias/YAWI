package de.yawi.installer.core.elevation;

import de.yawi.installer.core.download.HttpDownloader;
import de.yawi.installer.core.download.SourceResolver;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.Engine;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.FailurePrompt;
import de.yawi.installer.core.engine.InstallRunner;
import de.yawi.installer.core.engine.ProgressListener;
import de.yawi.installer.core.engine.Uninstaller;
import de.yawi.installer.core.error.ElevationUnavailableException;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestOrigin;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.TestPlatforms;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.RecordReader;
import de.yawi.installer.core.state.RecordWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * E14-S03 (elevated uninstall): the hand-over to a second installer process
 * that removes an installation, without a rights prompt ({@link Elevation#direct()}).
 * The child is a real process started through {@link SelfCommand}.
 */
class ElevatedUninstallTest {

    @TempDir
    Path tmp;

    private Platform platform;
    private Path home;
    private final InstallManifest manifest = TestManifests.bundled();

    @BeforeEach
    void scratch() {
        home = tmp.resolve("home");
        platform = TestPlatforms.scratch(home);
        assumeFalse(platform.isElevated(), "as root the user-space guard behaves differently");
    }

    private Path install(Set<String> components) {
        Path dest = tmp.resolve("dest");
        InstallRunner.Request request = InstallRunner.Request.of(manifest, platform, dest, components,
                Map.of(), Map.of(), Optional.empty());
        Engine.Result result = new InstallRunner(new SourceResolver(new HttpDownloader()))
                .run(request, ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
        assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
        return dest;
    }

    private UninstallPlan planFor(Path dest) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String n : List.of("HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "PATH")) {
            platform.environment().env(n).ifPresent(v -> env.put(n, v));
        }
        Map<String, String> props = new LinkedHashMap<>();
        for (String n : List.of("user.home", "java.io.tmpdir")) {
            platform.environment().property(n).ifPresent(v -> props.put(n, v));
        }
        return new UninstallPlan(dest, false, false, manifest.origin(), env, props);
    }

    private static boolean noHandoverLeft(Platform platform) throws IOException {
        Path work = ExecutionContext.workDir(platform);
        if (!Files.isDirectory(work)) {
            return true;
        }
        try (Stream<Path> list = Files.list(work)) {
            return list.noneMatch(p -> p.getFileName().toString().startsWith("elevated-"));
        }
    }

    @Test
    void planRoundTrips() throws Exception {
        UninstallPlan plan = new UninstallPlan(Path.of("/opt/app"), true, false,
                new ManifestOrigin(ManifestOrigin.Kind.CLASSPATH, "classpath:/installer.xml",
                        ManifestOrigin.Signature.UNSIGNED),
                Map.of("HOME", "/home/tom"), Map.of("user.home", "/home/tom"));
        Path file = tmp.resolve("uninstall.xml");
        plan.write(file);
        UninstallPlan back = UninstallPlan.read(file);
        assertEquals(plan, back);
    }

    @Test
    void resultRoundTripsAReport() throws Exception {
        Uninstaller.Report report = new Uninstaller.Report(3, 1, List.of("keep.cfg"), List.of("user.dat"),
                List.of("some-step"), List.of(), false, true);
        Path file = tmp.resolve("result.xml");
        UninstallResult.ofReport(report).write(file);
        UninstallResult back = UninstallResult.read(file).orElseThrow();
        assertTrue(back.report().isPresent());
        assertEquals(report, back.report().get());
        assertTrue(back.succeeded());
    }

    @Test
    void userSpaceDestinationRunsInProcessEvenWithAnElevationAvailable() {
        Path dest = install(Set.of("core"));
        InstallationRecord record = readRecord(dest);
        // The gate must not elevate a destination the user can write.
        Uninstaller.Report report = new Uninstaller(manifest, platform, record, Optional.empty(),
                Optional.of(Elevation.direct())).run(Uninstaller.Options.SAFE, ProgressListener.NOOP,
                new CancellationToken());
        assertTrue(report.clean(), report.failures().toString());
        assertFalse(Files.exists(dest.resolve("settings.ini")), "removed in process");
    }

    @Test
    void elevatedUninstallRemovesTheFilesThroughAChild() throws Exception {
        Path dest = install(Set.of("core"));
        assertTrue(Files.isRegularFile(dest.resolve("settings.ini")));

        Uninstaller.Report report;
        try (ElevatedUninstall child = ElevatedUninstall.prepare(Elevation.direct(), platform, manifest,
                planFor(dest), ProgressListener.NOOP, new CancellationToken())) {
            child.start();
            child.go();
            report = child.await();
        }
        assertTrue(report.clean(), report.failures().toString());
        assertTrue(report.deleted() > 0, "the child removed files");
        assertFalse(Files.exists(dest.resolve("settings.ini")), "the child removed the installation");
        assertTrue(noHandoverLeft(platform), "hand-over directory cleaned up");
    }

    @Test
    void noStrategyFailsWithElevationUnavailable() {
        Path dest = install(Set.of("core"));
        assertThrows(ElevationUnavailableException.class, () -> ElevatedUninstall.prepare(Elevation.none(),
                platform, manifest, planFor(dest), ProgressListener.NOOP, new CancellationToken()));
    }

    private InstallationRecord readRecord(Path dest) {
        try {
            return RecordReader.read(RecordWriter.defaultFile(dest));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
