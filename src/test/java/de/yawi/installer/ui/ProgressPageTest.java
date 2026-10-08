package de.yawi.installer.ui;

import de.yawi.installer.core.engine.Engine;
import de.yawi.installer.core.engine.StepOutcome;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRegistry;
import de.yawi.installer.core.state.RecordReader;
import de.yawi.installer.core.state.RecordWriter;
import de.yawi.installer.ui.WizardNavigationTest.Wizard;
import de.yawi.installer.ui.page.ProgressPageController;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Milestone 2 through the real wizard: the bundled manifest with
 * its demo payload installs into a temp folder; a failing manifest reports
 * on the page and on the finish page. Needs a JavaFX toolkit.
 */
class ProgressPageTest {

    @TempDir
    Path tmp;

    @BeforeAll
    static void startToolkit() {
        WizardNavigationTest.startToolkit();
    }

    private static ProgressPageController progressPage(Wizard w) {
        return (ProgressPageController) w.flow().getCurrentPage();
    }

    private static String text(Wizard w, String fxId) {
        return ((Labeled) w.root().lookup("#" + fxId)).getText();
    }

    /** The log list sits inside the (unskinned) TitledPane, where lookup cannot see it. */
    @SuppressWarnings("unchecked")
    private static ListView<String> logOf(Wizard w) {
        return (ListView<String>) ((TitledPane) w.root().lookup("#details")).getContent();
    }

    @Test
    void fullInstallationFromBundledDataSucceeds() throws Exception {
        Path dest = tmp.resolve("YOUR INSTALLER");
        InstallationRegistry registry = InstallationRegistry.at(tmp.resolve("registry"), "your-product");
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = WizardNavigationTest.wizard(TestManifests.bundled(), Locale.ENGLISH);
            w.model().setDestination(dest);
            w.model().setRegistry(registry); // what WizardApp gives the model
            w.advanceTo("components");
            w.model().getSelectedComponents().add("server");
            ((TextField) w.root().lookup("#input-serverPort")).setText("51234");
            w.advanceTo("progress");
            assertTrue(w.button("nextButton").isDisabled(), "locked while installing");
            assertTrue(w.button("backButton").isDisabled(), "no way back");

            Engine.Result result = WizardNavigationTest.await(progressPage(w).result());

            assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
            assertEquals(8, result.reports().size()); // five manifest steps + shortcut + association + PATH
            assertTrue(result.reports().stream().allMatch(r -> r.outcome() == StepOutcome.DONE));
            assertFalse(w.button("nextButton").isDisabled(), "unlocked after the task");
            assertEquals("The installation completed successfully.", text(w, "outcome"));
            assertEquals("Step 8 of 8: PATH entries", text(w, "stepLabel"));
            assertEquals(1.0, ((ProgressBar) w.root().lookup("#overallProgress")).getProgress(), 1e-9);
            assertTrue(text(w, "elapsed").startsWith("Elapsed: "), text(w, "elapsed"));
            ListView<String> log = logOf(w);
            assertTrue(log.getItems().stream().anyMatch(l -> l.startsWith("$ ")), "command echoed: " + log.getItems());
            if (PlatformFactory.current().os() != OperatingSystem.WINDOWS) {
                assertTrue(log.getItems().contains("registering su-server in " + dest), log.getItems().toString());
            }
            assertTrue(w.model().isInstallSucceeded());

            // Language switch re-renders the page texts.
            w.model().setLocale(Locale.GERMAN);
            assertEquals("Die Installation wurde erfolgreich abgeschlossen.", text(w, "outcome"));
            assertEquals("Schritt 8 von 8: PATH-Einträge", text(w, "stepLabel"));
            w.model().setLocale(Locale.ENGLISH);

            w.advanceTo("finish");
            assertTrue(WizardNavigationTest.visibleTexts(w.root()).contains("YOUR INSTALLER has been installed successfully."));
        });

        assertTrue(Files.isRegularFile(dest.resolve("data/maps/green-valley.map")));
        assertTrue(Files.readString(dest.resolve("settings.ini")).contains("port=51234"));
        assertTrue(Files.isRegularFile(dest.resolve("bin/su-server")));
        InstallationRecord record = RecordReader.read(RecordWriter.defaultFile(dest));
        assertEquals("51234", record.inputs().get("serverPort"));
        assertTrue(record.createdPaths().contains("bin/su-server"), record.createdPaths().toString());
        assertTrue(Files.readString(RecordWriter.defaultFile(dest)).endsWith("</record>\n"), "record closed");
        assertEquals(1, registry.list().size(), "the wizard's installation is registered");
        assertEquals(dest.toAbsolutePath().normalize(), registry.list().get(0).destination());
        assertTrue(registry.installations().get(0).isIntact());
    }

    @Test
    void downloadedSourceIsFetchedShownAndCleanedUp() throws Exception {
        cleanDownloads();
        Path dest = tmp.resolve("http-install");
        try (de.yawi.installer.core.download.TestHttpServerAccess server = new de.yawi.installer.core.download.TestHttpServerAccess()) {
            server.customBytes(TestManifests.bytes("/archives/sample.zip"));
            String xml = new String(TestManifests.bytes("/manifest/download/http-install.xml"), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("__BASE__", server.base());
            de.yawi.installer.core.manifest.InstallManifest manifest = new de.yawi.installer.core.manifest.ManifestParser()
                    .parse(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8), TestManifests.origin("/manifest/download/http-install.xml"));
            List<String> stepLabels = new java.util.ArrayList<>();
            WizardNavigationTest.onFxThread(() -> {
                Wizard w = WizardNavigationTest.wizard(manifest, Locale.ENGLISH);
                w.model().setDestination(dest);
                w.advanceTo("summary");
                assertTrue(WizardNavigationTest.visibleTexts(w.root()).contains("data (download)"));
                w.advance();
                assertEquals("progress", w.flow().getCurrentPage().name(), "source page skipped: nothing to choose");
                ((Labeled) w.root().lookup("#stepLabel")).textProperty().addListener((o, a, b) -> stepLabels.add(b));

                Engine.Result result = WizardNavigationTest.await(progressPage(w).result());

                assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
                assertTrue(stepLabels.contains("Downloading data (1 of 1)"), stepLabels.toString());
                assertEquals("Step 1 of 1: unpack", text(w, "stepLabel"));
                assertEquals(1.0, ((ProgressBar) w.root().lookup("#overallProgress")).getProgress(), 1e-9);
                ListView<String> log = logOf(w);
                assertTrue(log.getItems().stream().noneMatch(l -> l.startsWith("retry")), "404 mirror skipped without retry: " + log.getItems());
            });
            assertEquals("sample archive\n", Files.readString(dest.resolve("README.txt")), "downloaded zip was extracted");
            Path downloads = de.yawi.installer.core.engine.ExecutionContext.workDir(PlatformFactory.current()).resolve("downloads/data");
            assertFalse(Files.exists(downloads.resolve("custom")), "download deleted after success");
        }
    }

    /** E10-S01: the downloaded file does not match the manifest's sha256 - the installation stops with a clear message. */
    @Test
    void downloadWithWrongChecksumIsRejected() throws Exception {
        cleanDownloads();
        Path dest = tmp.resolve("http-bad");
        try (de.yawi.installer.core.download.TestHttpServerAccess server = new de.yawi.installer.core.download.TestHttpServerAccess()) {
            server.customBytes("not the archive you expected".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String xml = new String(TestManifests.bytes("/manifest/download/http-install.xml"), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("__BASE__", server.base());
            de.yawi.installer.core.manifest.InstallManifest manifest = new de.yawi.installer.core.manifest.ManifestParser()
                    .parse(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8), TestManifests.origin("/manifest/download/http-install.xml"));
            WizardNavigationTest.onFxThread(() -> {
                Wizard w = WizardNavigationTest.wizard(manifest, Locale.ENGLISH);
                w.model().setDestination(dest);
                w.advanceTo("progress");

                Engine.Result result = WizardNavigationTest.await(progressPage(w).result());

                assertFalse(result.succeeded());
                assertEquals(de.yawi.installer.core.error.ErrorCode.INTEGRITY_FAILED, result.failure().orElseThrow().code());
                assertEquals("The downloaded file data is damaged or has been tampered with.", text(w, "outcome"));
                assertTrue(logOf(w).getItems().contains("http: checksum mismatch"), logOf(w).getItems().toString());
            });
            assertFalse(Files.exists(dest.resolve("README.txt")), "nothing extracted");
            Path downloads = de.yawi.installer.core.engine.ExecutionContext.workDir(PlatformFactory.current()).resolve("downloads/data");
            assertFalse(Files.exists(downloads.resolve("custom")), "wrong file deleted");
            assertFalse(Files.exists(downloads.resolve("custom.part")));
        }
    }

    /** E08: the source comes over BitTorrent from an in-process seeder; the page shows it like any download. */
    @Test
    void torrentSourceIsDownloadedFromTheSeeder() throws Exception {
        cleanDownloads();
        Path dest = tmp.resolve("torrent-install");
        try (de.yawi.installer.core.download.TestHttpServerAccess server = new de.yawi.installer.core.download.TestHttpServerAccess();
             de.yawi.installer.core.download.torrent.TorrentTestSupport swarm =
                     de.yawi.installer.core.download.torrent.TorrentTestSupport.create(tmp, "sample.zip", TestManifests.bytes("/archives/sample.zip"))) {
            swarm.startSeeder();
            de.yawi.installer.core.download.torrent.TorrentRuntime runtime = new de.yawi.installer.core.download.torrent.TorrentRuntime(
                    de.yawi.installer.core.download.torrent.TorrentTestSupport.TEST_CONFIG, List.of(swarm.fixedPeers()));
            try {
                de.yawi.installer.core.manifest.InstallManifest manifest = torrentManifest(server, swarm.writeTorrentFile(tmp.resolve("data.torrent")));
                List<String> stepLabels = new java.util.ArrayList<>();
                WizardNavigationTest.onFxThread(() -> {
                    Wizard w = WizardNavigationTest.wizard(manifest, Locale.ENGLISH);
                    w.model().setSourceResolver(new de.yawi.installer.core.download.SourceResolver(
                            new de.yawi.installer.core.download.HttpDownloader(), new de.yawi.installer.core.download.TorrentDownloader(runtime)));
                    w.model().setDestination(dest);
                    w.advanceTo("source");
                    ((javafx.scene.control.RadioButton) w.root().lookup("#source-data-torrent")).setSelected(true);
                    w.advanceTo("summary");
                    assertTrue(WizardNavigationTest.visibleTexts(w.root()).contains("data (BitTorrent)"));
                    w.advance();
                    ((Labeled) w.root().lookup("#stepLabel")).textProperty().addListener((o, a, b) -> stepLabels.add(b));

                    Engine.Result result = WizardNavigationTest.await(progressPage(w).result());

                    assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
                    assertTrue(stepLabels.contains("Downloading data (1 of 1)"), stepLabels.toString());
                    assertTrue(stepLabels.contains("Verifying data…"), "whole-file checksum: " + stepLabels);
                    assertTrue(logOf(w).getItems().stream().noneMatch(l -> l.contains("trying")), logOf(w).getItems().toString());
                });
                assertEquals("sample archive\n", Files.readString(dest.resolve("README.txt")), "torrented zip was extracted");
                assertFalse(runtime.isStarted(), "runtime disposed of after the download");
            } finally {
                runtime.shutdown();
            }
        }
    }

    /** E08-S03: no peer within peerTimeout - the installation continues over the HTTP mirror and says so. */
    @Test
    void torrentWithoutPeersFallsBackToHttp() throws Exception {
        cleanDownloads();
        Path dest = tmp.resolve("torrent-fallback");
        try (de.yawi.installer.core.download.TestHttpServerAccess server = new de.yawi.installer.core.download.TestHttpServerAccess();
             de.yawi.installer.core.download.torrent.TorrentTestSupport swarm =
                     de.yawi.installer.core.download.torrent.TorrentTestSupport.create(tmp, "sample.zip", TestManifests.bytes("/archives/sample.zip"))) {
            server.customBytes(TestManifests.bytes("/archives/sample.zip"));
            // No seeder, no peer source: the torrent can only time out.
            de.yawi.installer.core.download.torrent.TorrentRuntime runtime = new de.yawi.installer.core.download.torrent.TorrentRuntime(
                    de.yawi.installer.core.download.torrent.TorrentTestSupport.TEST_CONFIG, List.of());
            try {
                de.yawi.installer.core.manifest.InstallManifest manifest = torrentManifest(server, swarm.writeTorrentFile(tmp.resolve("data.torrent")));
                WizardNavigationTest.onFxThread(() -> {
                    Wizard w = WizardNavigationTest.wizard(manifest, Locale.ENGLISH);
                    w.model().setSourceResolver(new de.yawi.installer.core.download.SourceResolver(
                            new de.yawi.installer.core.download.HttpDownloader(), new de.yawi.installer.core.download.TorrentDownloader(runtime)));
                    w.model().setDestination(dest);
                    w.model().getSourceChoices().put("data", de.yawi.installer.core.download.ProviderKind.TORRENT);
                    w.advanceTo("progress");

                    Engine.Result result = WizardNavigationTest.await(progressPage(w).result());

                    assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
                    assertTrue(logOf(w).getItems().contains("data: BitTorrent failed (no peers within 5s), trying download"),
                            logOf(w).getItems().toString());
                });
                assertEquals("sample archive\n", Files.readString(dest.resolve("README.txt")), "zip came over HTTP");
            } finally {
                runtime.shutdown();
            }
        }
    }

    /** The download folder of source "data" is shared through the platform's work dir; a failed run must not leak into the next. */
    private static void cleanDownloads() throws IOException {
        Path downloads = de.yawi.installer.core.engine.ExecutionContext.workDir(PlatformFactory.current()).resolve("downloads/data");
        if (Files.isDirectory(downloads)) {
            try (var files = Files.walk(downloads)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    private static de.yawi.installer.core.manifest.InstallManifest torrentManifest(
            de.yawi.installer.core.download.TestHttpServerAccess server, Path torrentFile) {
        String xml = new String(TestManifests.bytes("/manifest/download/torrent-install.xml"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("__BASE__", server.base())
                .replace("__TORRENT__", torrentFile.toString());
        return new de.yawi.installer.core.manifest.ManifestParser()
                .parse(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8), TestManifests.origin("/manifest/download/torrent-install.xml"));
    }

    /** E10-S01: bundled data with a sha256 is read once before the steps; the page shows it. */
    @Test
    void bundledDataIsVerifiedAndShown() throws Exception {
        Path dest = tmp.resolve("verified");
        List<String> stepLabels = new java.util.ArrayList<>();
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = WizardNavigationTest.wizard(TestManifests.bundled(), Locale.ENGLISH);
            w.model().setDestination(dest);
            w.advanceTo("progress");
            ((Labeled) w.root().lookup("#stepLabel")).textProperty().addListener((o, a, b) -> stepLabels.add(b));

            Engine.Result result = WizardNavigationTest.await(progressPage(w).result());

            assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
            assertTrue(stepLabels.contains("Verifying game-data…"), stepLabels.toString());
            assertTrue(stepLabels.stream().noneMatch(l -> l.startsWith("Downloading")), stepLabels.toString());
        });
    }

    @Test
    void failingStepIsReportedOnThePageAndTheFinishPage() throws Exception {
        Path dest = tmp.resolve("failing");
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = WizardNavigationTest.wizard(TestManifests.parse("/manifest/engine/failing-install.xml"), Locale.ENGLISH);
            w.model().setDestination(dest);
            w.advanceTo("progress");

            Engine.Result result = WizardNavigationTest.await(progressPage(w).result());

            assertFalse(result.succeeded());
            assertEquals(List.of("mk", "boom"), result.reports().stream().map(r -> r.step().id()).toList());
            assertFalse(w.model().isInstallSucceeded());
            assertFalse(w.button("nextButton").isDisabled(), "the user can still reach the finish page");
            assertEquals("Installation step boom failed: exit code 2 (expected 0)", text(w, "outcome"));
            assertTrue(text(w, "outcomeHint").contains("log"), text(w, "outcomeHint"));
            // The rollback reports in the hint and takes over the step label.
            assertTrue(text(w, "outcomeHint").contains("The changes made so far were undone"), text(w, "outcomeHint"));
            assertTrue(text(w, "outcomeHint").contains("cannot be undone automatically: boom"), text(w, "outcomeHint"));
            assertTrue(text(w, "stepLabel").matches("Undoing changes \\(\\d+ of \\d+\\)"), text(w, "stepLabel"));
            assertTrue(w.root().lookup("#outcome").getStyleClass().contains("warning"));
            ListView<String> log = logOf(w);
            assertEquals(1, log.getItems().stream().filter("going wrong"::equals).count(), "not duplicated: " + log.getItems());
            assertTrue(log.getItems().stream().anyMatch(l -> l.startsWith("Changes undone")), log.getItems().toString());
            assertTrue(((TitledPane) w.root().lookup("#details")).isExpanded(), "details opened on failure");

            w.model().setLocale(Locale.GERMAN);
            assertTrue(text(w, "stepLabel").startsWith("Änderungen werden zurückgenommen"), text(w, "stepLabel"));
            assertTrue(text(w, "outcome").startsWith("Installationsschritt boom ist fehlgeschlagen"), text(w, "outcome"));
            assertTrue(text(w, "outcomeHint").contains("wurden zurückgenommen"), text(w, "outcomeHint"));

            w.advanceTo("finish");
            assertTrue(WizardNavigationTest.visibleTexts(w.root()).contains("Die Installation von Failing wurde nicht abgeschlossen."),
                    WizardNavigationTest.visibleTexts(w.root()).toString());
        });
        // Rolled back: the folder the run created is gone altogether.
        assertFalse(Files.exists(dest), "destination rolled back to nothing");
    }

    @Test
    void cancelDuringAStepRollsBackAndKeepsTheWindowOpen() throws Exception {
        assumeTrue(de.yawi.installer.core.platform.PlatformFactory.current().os()
                != de.yawi.installer.core.platform.OperatingSystem.WINDOWS, "sleep via /bin/sh");
        Path dest = tmp.resolve("cancelling");
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = WizardNavigationTest.wizard(TestManifests.parse("/manifest/engine/cancel-install.xml"), Locale.ENGLISH);
            w.model().setDestination(dest);
            w.advanceTo("progress");
            // Wait until the blocking step runs, then cancel the way the frame does after the dialog.
            CompletableFuture<Void> waiting = new CompletableFuture<>();
            Labeled stepLabel = (Labeled) w.root().lookup("#stepLabel");
            stepLabel.textProperty().addListener((obs, old, now) -> {
                if (now != null && now.contains("Wait")) {
                    waiting.complete(null);
                }
            });
            if (!stepLabel.getText().contains("Wait")) {
                WizardNavigationTest.await(waiting);
            }
            assertTrue(Files.isDirectory(dest.resolve("made")), "the first step ran");

            assertTrue(w.model().requestCancel(), "the progress page takes the cancel");
            Engine.Result result = WizardNavigationTest.await(progressPage(w).result());

            assertTrue(result.isCancelled());
            assertTrue(result.rollback().orElseThrow().clean(), result.rollback().toString());
            // (The frame returns without close() when requestCancel() is true - that is the branch taken above.)
            assertFalse(w.button("cancelButton").isDisabled(), "cancel is usable again once the rollback is through");
            assertFalse(w.button("nextButton").isDisabled(), "the user can reach the finish page");
            assertEquals("The installation was cancelled and the changes made so far were undone.", text(w, "outcome"));
            assertTrue(text(w, "stepLabel").matches("Undoing changes \\(\\d+ of \\d+\\)"), text(w, "stepLabel"));
        });
        assertFalse(Files.exists(dest), "destination rolled back to nothing");
    }

    /** The elevated block runs in a second process, here without a prompt: the page shows it like any step. */
    @Test
    void elevatedStepsRunThroughTheChildProcess() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(de.yawi.installer.core.platform.PlatformFactory.current().isElevated());
        Path dest = tmp.resolve("elevated");
        InstallManifest manifest = TestManifests.parse("/manifest/engine/elevated.xml");
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = WizardNavigationTest.wizard(manifest, Locale.ENGLISH);
            w.model().setDestination(dest);
            w.model().setElevation(de.yawi.installer.core.elevation.Elevation.direct());
            w.advanceTo("progress");
            Engine.Result result = WizardNavigationTest.await(progressPage(w).result());
            assertTrue(result.succeeded(), result.failure().map(Throwable::toString).orElse("?"));
            assertEquals(3, result.reports().size());
            assertTrue(Files.isRegularFile(dest.resolve("service.txt")));
            assertTrue(text(w, "stepLabel").contains("3 of 3"), text(w, "stepLabel"));
            assertTrue(logOf(w).getItems().stream().anyMatch(l -> l.contains("service.txt")), "child's output relayed");
        });
    }
}
