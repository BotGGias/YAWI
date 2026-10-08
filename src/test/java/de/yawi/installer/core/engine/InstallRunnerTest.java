package de.yawi.installer.core.engine;

import de.yawi.installer.core.download.HttpDownloader;
import de.yawi.installer.core.download.LocalCache;
import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.download.SourceResolver;
import de.yawi.installer.core.download.TestHttpServerAccess;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestParser;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.platform.TestPlatforms;
import de.yawi.installer.core.state.InstallationRegistry;
import de.yawi.installer.core.state.RecordReader;
import de.yawi.installer.core.state.RecordWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.util.HexFormat;
import java.util.TreeMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The headless run the wizard and the silent mode share: sources, plan, record, engine, clean-up. */
class InstallRunnerTest {

    @TempDir
    Path tmp;

    /** This OS with the temp folder as home, so the bundled manifest's shortcut lands there. */
    private Platform platform;

    @org.junit.jupiter.api.BeforeEach
    void scratchPlatform() {
        platform = TestPlatforms.scratch(tmp.resolve("home"));
    }

    @Test
    void bundledManifestInstallsThroughTheRunner() throws Exception {
        InstallManifest manifest = TestManifests.bundled();
        Path dest = tmp.resolve("bundled");
        InstallRunner.Request request = InstallRunner.Request.of(manifest, platform, dest, Set.of("core"),
                Map.of(), Map.of(), Optional.empty());
        assertEquals(Set.of("core"), request.selected());
        assertEquals(ProviderKind.BUNDLED, request.choices().get("game-data"), "shipped copy wins by default");
        assertEquals(List.of(), request.downloads());

        List<String> dryRun = new InstallRunner(new SourceResolver(new HttpDownloader())).dryRun(request);
        // core's two steps + the shortcut + the association + the PATH step
        assertEquals(5, dryRun.size(), dryRun.toString());
        assertFalse(Files.exists(dest), "dry run writes nothing");

        Engine.Result result = new InstallRunner(new SourceResolver(new HttpDownloader()))
                .run(request, ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken());

        assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
        assertTrue(Files.isRegularFile(dest.resolve("settings.ini")));
        assertEquals(List.of("core"), RecordReader.read(RecordWriter.defaultFile(dest)).components());
    }

    @Test
    void cachedSourceIsUsedAndKeptDownloadedOneIsDeleted() throws Exception {
        byte[] zip = TestManifests.bytes("/archives/sample.zip");
        try (TestHttpServerAccess server = new TestHttpServerAccess()) {
            server.customBytes(zip);
            String xml = new String(TestManifests.bytes("/manifest/download/http-install.xml"), StandardCharsets.UTF_8)
                    .replace("__BASE__", server.base());
            InstallManifest manifest = new ManifestParser().parse(xml.getBytes(StandardCharsets.UTF_8),
                    TestManifests.origin("/manifest/download/http-install.xml"));

            // 1. From the cache: the file is listed under the source's checksum, the server is never asked.
            Path cacheDir = tmp.resolve("cache");
            Files.createDirectories(cacheDir.resolve("launcher"));
            Files.write(cacheDir.resolve("launcher/data.zip"), zip);
            Files.writeString(cacheDir.resolve(LocalCache.INDEX_FILE),
                    "d8e1dea8f94870e66a0a34395e895bcbcab4245f99ec96f4b868beda1d315962  launcher/data.zip\n");
            Path dest = tmp.resolve("cached");
            InstallRunner.Request request = InstallRunner.Request.of(manifest, platform, dest, Set.of(), Map.of(),
                    Map.of(), Optional.of(LocalCache.open(cacheDir)));
            List<String> events = new ArrayList<>();
            Engine.Result result = new InstallRunner(new SourceResolver(new HttpDownloader())).run(request,
                    new ProgressListener() {
                        @Override
                        public void downloadVerifying(de.yawi.installer.core.manifest.Source source) {
                            events.add("verifying " + source.id());
                        }

                        @Override
                        public void output(String line) {
                            events.add(line);
                        }
                    }, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
            assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
            assertTrue(Files.isRegularFile(dest.resolve("README.txt")));
            assertTrue(events.contains("verifying data"), events.toString());
            assertTrue(events.contains("source data: from cache data.zip"), events.toString());
            assertTrue(Files.exists(cacheDir.resolve("launcher/data.zip")), "cache untouched after success");

            // 2. Without a cache: downloaded, then removed after success.
            Path dest2 = tmp.resolve("downloaded");
            InstallRunner.Request request2 = InstallRunner.Request.of(manifest, platform, dest2, Set.of(), Map.of(),
                    Map.of(), Optional.empty());
            Engine.Result result2 = new InstallRunner(new SourceResolver(new HttpDownloader())).run(request2,
                    ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
            assertTrue(result2.succeeded(), result2.failure().map(Throwable::getMessage).orElse(""));
            assertTrue(Files.isRegularFile(dest2.resolve("README.txt")));
            Path downloads = ExecutionContext.workDir(platform).resolve("downloads/data");
            assertFalse(Files.exists(downloads.resolve("custom")), "download deleted after success");
        }
    }

    // --- rollback ------------------------------------------------

    private static Engine.Result run(InstallManifest manifest, Path dest, Set<String> selection) {
        InstallRunner.Request request = InstallRunner.Request.of(manifest, PlatformFactory.current(), dest, selection,
                Map.of(), Map.of(), Optional.empty());
        return new InstallRunner(new SourceResolver(new HttpDownloader()))
                .run(request, ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
    }

    /** E14-S01-T03: a finished installation is registered; a rolled-back or dry run is not. */
    @Test
    void successRegistersTheInstallationFailureAndDryRunDoNot() throws Exception {
        InstallManifest manifest = TestManifests.parse("/manifest/engine/failing-update.xml");
        InstallationRegistry registry = InstallationRegistry.at(tmp.resolve("registry"), "rollback");
        InstallRunner runner = new InstallRunner(new SourceResolver(new HttpDownloader()), Optional.of(registry));
        Path dest = tmp.resolve("registered");

        InstallRunner.Request broken = InstallRunner.Request.of(manifest, platform, dest, Set.of("core", "broken"),
                Map.of(), Map.of(), Optional.empty());
        runner.dryRun(broken);
        assertTrue(registry.list().isEmpty(), "a dry run registers nothing");
        assertFalse(runner.run(broken, ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken())
                .succeeded());
        assertTrue(registry.list().isEmpty(), "a rolled-back run registers nothing");

        InstallRunner.Request good = InstallRunner.Request.of(manifest, platform, dest, Set.of("core"),
                Map.of(), Map.of(), Optional.empty());
        assertTrue(runner.run(good, ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken())
                .succeeded());
        List<InstallationRegistry.Registration> listed = registry.list();
        assertEquals(1, listed.size());
        assertEquals(dest.toAbsolutePath().normalize(), listed.get(0).destination());
        assertEquals("2", listed.get(0).productVersion());
        assertTrue(registry.installations().get(0).isIntact());
    }

    @Test
    void unwritableRegisterDoesNotFailTheInstallation() throws Exception {
        InstallManifest manifest = TestManifests.parse("/manifest/engine/failing-update.xml");
        Path blocker = Files.writeString(tmp.resolve("not-a-directory"), "file");
        InstallationRegistry registry = InstallationRegistry.at(blocker, "rollback");
        InstallRunner runner = new InstallRunner(new SourceResolver(new HttpDownloader()), Optional.of(registry));
        Path dest = tmp.resolve("unregistered");

        InstallRunner.Request good = InstallRunner.Request.of(manifest, platform, dest, Set.of("core"),
                Map.of(), Map.of(), Optional.empty());
        Engine.Result result = runner.run(good, ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS,
                new CancellationToken());

        assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
        assertTrue(Files.isRegularFile(RecordWriter.defaultFile(dest)));
        assertTrue(registry.list().isEmpty());
    }

    /** Every file below {@code root} with its bytes, so two trees can be compared. */
    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> tree = new TreeMap<>();
        if (!Files.exists(root)) {
            return tree;
        }
        try (var walk = Files.walk(root)) {
            for (Path p : walk.toList()) {
                String key = root.relativize(p).toString().replace('\\', '/');
                tree.put(key, Files.isDirectory(p) ? "<dir>" : HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return tree;
    }

    @Test
    void failingStepRollsBackAFreshInstallToNothing() throws Exception {
        InstallManifest manifest = TestManifests.parse("/manifest/engine/failing-update.xml");
        Path dest = tmp.resolve("fresh");

        Engine.Result result = run(manifest, dest, Set.of("core", "broken"));

        assertFalse(result.succeeded());
        Rollback.Report report = result.rollback().orElseThrow();
        assertTrue(report.clean(), report.toString());
        assertFalse(Files.exists(dest), "the folder the run created is gone: " + snapshot(dest));

        // With a foreign file in an existing folder: only the foreign file remains.
        Path existing = Files.createDirectories(tmp.resolve("existing"));
        Files.writeString(existing.resolve("mine.txt"), "mine\n");
        Map<String, String> before = snapshot(existing);
        result = run(manifest, existing, Set.of("core", "broken"));
        assertFalse(result.succeeded());
        assertTrue(result.rollback().orElseThrow().clean(), result.rollback().toString());
        assertEquals(before, snapshot(existing));
    }

    @Test
    void failingStepRollsBackAnUpdateAndRestoresTheOldRecord() throws Exception {
        InstallManifest manifest = TestManifests.parse("/manifest/engine/failing-update.xml");
        Path dest = tmp.resolve("update");
        // The previous installation: a successful run of version 2, then a file the "update" will replace.
        assertTrue(run(manifest, dest, Set.of("core")).succeeded());
        Files.writeString(dest.resolve("README.txt"), "edited by the user\n");
        Path recordFile = RecordWriter.defaultFile(dest);
        String oldRecord = Files.readString(recordFile);
        Map<String, String> before = snapshot(dest);

        Engine.Result result = run(manifest, dest, Set.of("core", "broken"));

        assertFalse(result.succeeded());
        Rollback.Report report = result.rollback().orElseThrow();
        assertTrue(report.clean(), report.toString());
        assertEquals(before, snapshot(dest), "byte-identical to before the update");
        assertEquals(oldRecord, Files.readString(recordFile), "the previous record is back");
        assertFalse(Files.exists(dest.resolve(".installer/backup")));
        assertTrue(report.restored() > 0 && report.stepsWithoutReverse().contains("boom"), report.toString());
    }

    @Test
    void successDiscardsTheBackup() throws Exception {
        InstallManifest manifest = TestManifests.parse("/manifest/engine/failing-update.xml");
        Path dest = Files.createDirectories(tmp.resolve("ok"));
        Files.writeString(dest.resolve("README.txt"), "old\n");

        Engine.Result result = run(manifest, dest, Set.of("core"));

        assertTrue(result.succeeded());
        assertTrue(result.rollback().isEmpty());
        assertEquals("sample archive\n", Files.readString(dest.resolve("README.txt")));
        assertFalse(Files.exists(dest.resolve(".installer/backup")), "no backup after success");
        assertTrue(Files.readString(RecordWriter.defaultFile(dest)).contains("<replaced path=\"README.txt\""));
    }
}
