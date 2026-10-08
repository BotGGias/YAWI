package de.yawi.installer.ui;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.platform.TestPlatforms;
import de.yawi.installer.ui.page.DestinationPageController;
import de.yawi.installer.ui.page.ProgressPageController;
import de.yawi.installer.ui.i18n.I18nFxml;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Builds the real wizard (frame, flow, every page of the bundled manifest)
 * through the real {@code FXMLLoader}.
 *
 * <p>{@link FxmlContractTest} only compares signatures and {@link WizardFlowTest}
 * uses fake pages; this one proves that the pages actually load, that the
 * frame's buttons follow the flow and that a language switch updates the
 * texts on screen (E03). It needs a JavaFX toolkit and therefore skips where
 * none can be started. E18-S04 replaces the skip with Monocle so it also runs
 * in CI.
 */
class WizardNavigationTest {

    private static boolean toolkitStarted;
    private static boolean toolkitAvailable;

    /** Shared by every toolkit test class; the toolkit can only be started once per JVM. */
    @BeforeAll
    static synchronized void startToolkit() {
        if (toolkitStarted) {
            return;
        }
        toolkitStarted = true;
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            toolkitAvailable = started.await(30, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            toolkitAvailable = true;
        } catch (Throwable e) {
            toolkitAvailable = false;
        }
        if (toolkitAvailable) {
            // Tests show and hide stages; hiding the last one must not stop
            // the toolkit for the tests that follow.
            Platform.setImplicitExit(false);
        }
    }

    /** Frame, flow and stage for one test. */
    record Wizard(Stage stage, InstallerModel model, WizardFrameController frame, WizardFlow flow) {
        Parent root() {
            return stage.getScene().getRoot();
        }

        Button button(String fxId) {
            return (Button) root().lookup("#" + fxId);
        }

        String pageTitle() {
            return ((Labeled) root().lookup("#pageTitle")).getText();
        }

        Parent contentArea() {
            return (Parent) root().lookup("#content");
        }

        /** Moves to the next page, accepting the license and awaiting the destination check on the way. */
        void advance() {
            if (flow.getCurrentPage().name().equals("license")) {
                ((CheckBox) root().lookup("#accept")).setSelected(true);
            }
            if (flow.getCurrentPage() instanceof DestinationPageController destination) {
                await(destination.checkNow());
            }
            if (flow.getCurrentPage() instanceof ProgressPageController progress) {
                await(progress.result()); // the installation into the scratch folder
            }
            assertTrue(flow.canGoNextProperty().get(), "stuck on " + flow.getCurrentPage().name());
            flow.next();
        }

        void advanceTo(String pageName) {
            while (!flow.getCurrentPage().name().equals(pageName)) {
                assertFalse(flow.isLast(), "page " + pageName + " not reached");
                advance();
            }
        }
    }

    /**
     * A wizard whose destination is a fresh temp folder, so a test that walks
     * through the progress page installs there and never into the real home
     * directory. Tests of the destination page itself use
     * {@link #wizardWithoutDestination}.
     */
    static Wizard wizard(InstallManifest manifest, Locale locale) {
        java.nio.file.Path dest = scratchDestination();
        // The platform's home is the scratch folder too: shortcuts must never reach the real menu.
        return wizard(manifest, locale, TestPlatforms.scratch(dest.resolveSibling("home")), dest);
    }

    static Wizard wizard(InstallManifest manifest, Locale locale, de.yawi.installer.core.platform.Platform platform,
                         java.nio.file.Path destination) {
        InstallerModel model = new InstallerModel(manifest, platform);
        model.setLocale(locale);
        Stage stage = new Stage();
        WizardFlow flow = new WizardFlow(PageRegistry.build(model));
        WizardFrameController frame = WizardFrameController.show(stage, model, flow);
        Wizard w = new Wizard(stage, model, frame, flow);
        w.model().setDestination(destination);
        return w;
    }

    /** A temp folder removed when the JVM exits. */
    static java.nio.file.Path scratchDestination() {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("su-wizard-test");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(dir)));
            return dir.resolve("dest");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void deleteTree(java.nio.file.Path dir) {
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }

    static Wizard wizardWithoutDestination(InstallManifest manifest, Locale locale) {
        InstallerModel model = new InstallerModel(manifest, PlatformFactory.current());
        model.setLocale(locale);
        Stage stage = new Stage();
        WizardFlow flow = new WizardFlow(PageRegistry.build(model));
        WizardFrameController frame = WizardFrameController.show(stage, model, flow);
        return new Wizard(stage, model, frame, flow);
    }

    @Test
    void everyPageLoadsAndTheFlowWalksThemInManifestOrder() throws Exception {
        onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled(), Locale.ENGLISH);
            List<String> expected = TestManifests.bundled().wizard().pages();
            List<String> visited = new ArrayList<>();

            assertTrue(w.button("backButton").isDisabled(), "Back must be disabled on the first page");
            while (true) {
                WizardPage page = w.flow.getCurrentPage();
                visited.add(page.name());
                assertEquals(List.of(), markedTexts(w.root()), "unbound %key placeholders on " + page.name());
                assertTrue(w.contentArea().getChildrenUnmodifiable().contains(page.content()),
                        "content area shows " + page.name());
                if (w.flow.isLast()) {
                    break;
                }
                w.advance();
            }
            assertEquals(expected, visited);
            assertTrue(w.flow.backLockedProperty().get(), "progress page locks Back");
            assertTrue(w.button("backButton").isDisabled());
        });
    }

    @Test
    void frameButtonsAndWindowFollowTheManifestAndTheFlow() throws Exception {
        onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled(), Locale.ENGLISH);
            assertEquals("Next", w.button("nextButton").getText());
            assertEquals("Cancel", w.button("cancelButton").getText());
            assertEquals("Previous", w.button("backButton").getText());
            assertEquals("Welcome", w.pageTitle());

            w.advanceTo("components");
            assertFalse(w.button("backButton").isDisabled());
            assertEquals("Setup type", w.pageTitle());

            w.advanceTo("summary");
            assertEquals("Install", w.button("nextButton").getText());
            w.advanceTo("finish");
            assertEquals("Finish", w.button("nextButton").getText());
            assertTrue(w.button("backButton").isDisabled());

            // Window size, title and icon come from the manifest.
            assertEquals(TestManifests.bundled().wizard().width(), (int) w.stage.getScene().getWidth());
            assertEquals("YOUR INSTALLER Setup", w.stage.getTitle());
            assertFalse(w.stage.getIcons().isEmpty(), "icon from product/icon");
            // The window's close button goes through the same confirmation as Cancel.
            assertTrue(w.stage.getOnCloseRequest() != null, "close request handler installed");
        });
    }

    @Test
    void languageSwitchUpdatesFrameAndPage() throws Exception {
        onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled(), Locale.ENGLISH);
            List<String> before = visibleTexts(w.root());
            assertTrue(before.contains("Welcome to the YOUR INSTALLER installer"), before.toString());

            // No reload, no page change: the bound texts follow the property.
            w.model.setLocale(Locale.GERMAN);

            List<String> after = visibleTexts(w.root());
            assertEquals("Weiter", w.button("nextButton").getText());
            assertEquals("Abbrechen", w.button("cancelButton").getText());
            assertEquals("Willkommen", w.pageTitle());
            assertTrue(after.contains("Willkommen zum YOUR INSTALLER-Installer"), after.toString());
            assertTrue(after.contains("Sprache des Setups wählen:"), after.toString());
            assertEquals("YOUR INSTALLER Setup", w.stage.getTitle());

            // The choice sticks for the pages that follow.
            w.advanceTo("components");
            assertEquals("Setup-Typ", w.pageTitle());
            assertEquals("Zurück", w.button("backButton").getText());
        });
    }

    @Test
    void manifestTextsFollowTheLanguage() throws Exception {
        onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled(), Locale.ENGLISH);
            w.advanceTo("components");
            // Cells only exist once the list has been laid out.
            w.stage.show();
            w.root().applyCss();
            w.root().layout();

            List<String> before = visibleTexts(w.root());
            assertTrue(before.contains("Game files (required)"), "expected manifest texts, got " + before);

            w.model.setLocale(Locale.GERMAN);

            List<String> after = visibleTexts(w.root());
            assertTrue(after.contains("Spieldateien (erforderlich)"), "expected translated manifest texts, got " + after);
            assertTrue(after.contains("Dedizierter Server"), after.toString());
            w.stage.hide();
        });
    }

    @Test
    void inputsSurviveGoingBack() throws Exception {
        onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled(), Locale.ENGLISH);
            w.advanceTo("components");
            w.model.getSelectedComponents().add("server"); // the port belongs to the server
            ((TextField) w.root().lookup("#input-serverPort")).setText("60000");

            w.flow.next();
            w.flow.back();

            assertEquals("60000", ((TextField) w.root().lookup("#input-serverPort")).getText());
            assertEquals("60000", w.model.getInputs().get("serverPort"));
        });
    }

    @Test
    void licenseMustBeAcceptedBeforeNext() throws Exception {
        onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled(), Locale.ENGLISH);
            w.flow.next();
            assertEquals("license", w.flow.getCurrentPage().name());
            assertTrue(w.button("nextButton").isDisabled(), "Next must wait for the check box");
            String text = ((TextArea) w.root().lookup("#licenseText")).getText();
            assertTrue(text.contains("END USER LICENSE AGREEMENT"), "license text loaded: " + text);

            w.flow.next();
            assertEquals("license", w.flow.getCurrentPage().name(), "must not move without acceptance");

            ((CheckBox) w.root().lookup("#accept")).setSelected(true);
            assertFalse(w.button("nextButton").isDisabled());
            w.flow.next();
            assertEquals("components", w.flow.getCurrentPage().name());
        });
    }

    @Test
    void licensePageIsSkippedWithoutALicense() throws Exception {
        onFxThread(() -> {
            // Same pages, but no <license>: the page must not appear.
            InstallManifest bundled = TestManifests.bundled();
            InstallManifest noLicense = new InstallManifest(bundled.schemaVersion(), bundled.origin(),
                    bundled.product(), bundled.languages(),
                    new de.yawi.installer.core.manifest.WizardConfig(bundled.wizard().pages(), null),
                    bundled.destination(), bundled.sources(), bundled.components(), bundled.presets(),
                    bundled.steps(), bundled.integration(), bundled.messages(), bundled.xmlBytes());
            Wizard w = wizard(noLicense, Locale.ENGLISH);
            w.flow.next();
            assertEquals("components", w.flow.getCurrentPage().name());
            w.flow.back();
            assertEquals("welcome", w.flow.getCurrentPage().name());
        });
    }

    @Test
    void summaryListsSelectionAndManifestOrigin() throws Exception {
        onFxThread(() -> {
            Wizard w = wizardWithoutDestination(TestManifests.bundled(), Locale.ENGLISH);
            w.model.getSelectedComponents().add("server");
            w.advanceTo("summary");

            List<String> texts = visibleTexts(w.root());
            assertTrue(texts.contains("Game files, Dedicated server"), texts.toString());
            assertTrue(texts.contains("game-data (shipped copy)"), texts.toString());
            assertTrue(texts.contains("750 MiB"), texts.toString());
            assertTrue(texts.contains("CLASSPATH (/installer.xml) — not signed"), texts.toString());
            assertTrue(texts.stream().anyMatch(t -> t.endsWith("your-product")), "destination default: " + texts);

            // Translated component names follow the language.
            w.model.setLocale(Locale.GERMAN);
            assertTrue(visibleTexts(w.root()).contains("Spieldateien, Dedizierter Server"));
            assertTrue(visibleTexts(w.root()).contains("Benötigter Platz:"));
        });
    }

    @Test
    void finishPageReportsTheOutcome() throws Exception {
        onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled(), Locale.ENGLISH);
            w.advanceTo("finish");
            List<String> texts = visibleTexts(w.root());
            assertTrue(texts.contains("YOUR INSTALLER has been installed successfully."), texts.toString());
            assertTrue(texts.contains("Launch YOUR INSTALLER now"), texts.toString());
            assertTrue(texts.contains("Open log"), texts.toString());
            assertTrue(texts.contains("Save report…"), texts.toString());
            // The report reflects the wizard's state and hides the user's identity.
            String report = Diagnostics.report(w.model, java.util.Optional.empty());
            assertTrue(report.contains("Components: core"), report);
            assertFalse(report.contains(System.getProperty("user.home")), "home masked");
            assertTrue(report.contains("Product: your-product"), report);

            w.model.setInstallSucceeded(false);
            assertTrue(visibleTexts(w.root()).contains("The installation of YOUR INSTALLER did not complete."));
            assertTrue(w.root().lookup("#launch").isDisabled());

            w.model.setInstallSucceeded(true);
            assertFalse(w.root().lookup("#launch").isDisabled());
            ((CheckBox) w.root().lookup("#launch")).setSelected(true);
            assertTrue(w.model.isLaunchAfterFinish());
        });
    }

    /** E13-S01: without a {@code <shortcut>} there is nothing to launch, so the finish page does not offer it. */
    @Test
    void finishPageHidesTheLaunchOptionWithoutAShortcut() throws Exception {
        onFxThread(() -> {
            String xml = new String(TestManifests.bytes("/installer.xml"), java.nio.charset.StandardCharsets.UTF_8);
            String noIntegration = xml.substring(0, xml.indexOf("<integration>"))
                    + xml.substring(xml.indexOf("</integration>") + "</integration>".length());
            InstallManifest manifest = new de.yawi.installer.core.manifest.ManifestParser().parse(
                    noIntegration.getBytes(java.nio.charset.StandardCharsets.UTF_8), TestManifests.origin("/installer.xml"));
            assertTrue(manifest.integration().shortcuts().isEmpty());
            Wizard w = wizard(manifest, Locale.ENGLISH);
            w.advanceTo("finish");
            assertFalse(w.root().lookup("#launch").isVisible());
            assertFalse(w.root().lookup("#launch").isManaged(), "takes no space either");
            assertFalse(w.model.isLaunchAfterFinish());
        });
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Waits on the JavaFX thread for a future that completes on the JavaFX
     * thread, pumping events meanwhile - the same nested loop that
     * {@code showAndWait} uses. Blocking with {@code get()} would deadlock.
     */
    @SuppressWarnings("unchecked")
    static <T> T await(CompletableFuture<T> future) {
        Object key = new Object();
        // Async so that exit is never called before the loop is entered.
        future.whenCompleteAsync((result, failure) ->
                Platform.exitNestedEventLoop(key, failure != null ? failure : result), Platform::runLater);
        Object outcome = Platform.enterNestedEventLoop(key);
        if (outcome instanceof Throwable failure) {
            throw new AssertionError("awaited future failed", failure);
        }
        return (T) outcome;
    }

    interface FxBody {
        void run() throws Exception;
    }

    static void onFxThread(FxBody body) throws Exception {
        assumeTrue(toolkitAvailable, "no JavaFX toolkit available");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });

        assumeTrue(done.await(60, TimeUnit.SECONDS), "JavaFX thread did not respond");
        if (failure.get() != null) {
            throw new AssertionError("wizard test failed on the JavaFX thread", failure.get());
        }
    }

    /** All label, button and column texts below {@code root}, in tree order. */
    static List<String> visibleTexts(Node root) {
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        return texts;
    }

    static List<String> markedTexts(Node root) {
        return visibleTexts(root).stream().filter(I18nFxml::isMarker).toList();
    }

    private static void collectTexts(Node node, List<String> into) {
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            into.add(labeled.getText());
        }
        if (node instanceof TableView<?> table) {
            for (TableColumnBase<?, ?> column : table.getColumns()) {
                into.add(column.getText());
            }
        }
        if (node instanceof ListView<?> list) {
            // Cells are not children of the ListView but of its skin's flow.
            list.lookupAll(".list-cell").forEach(cell -> {
                if (cell instanceof Labeled labeled && labeled.getText() != null) {
                    into.add(labeled.getText());
                }
            });
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectTexts(child, into);
            }
        }
    }
}
