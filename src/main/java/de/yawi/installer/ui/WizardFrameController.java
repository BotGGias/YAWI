package de.yawi.installer.ui;

import de.yawi.installer.core.engine.LaunchTarget;
import de.yawi.installer.core.manifest.ResourceRef;
import de.yawi.installer.core.manifest.WizardConfig;
import de.yawi.installer.core.platform.AppLauncher;
import de.yawi.installer.ui.i18n.I18nFxml;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;

/**
 * The wizard window: header with icon and page title, the content area, and
 * the Back / Next / Cancel bar — each exactly once. Pages only fill the
 * content area; navigation goes through the {@link WizardFlow}.
 */
public class WizardFrameController {

    private static final String FRAME_FXML = "/de/yawi/installer/ui/wizard_frame.fxml";
    private static final String STYLESHEET = "/de/yawi/installer/ui/wizard.css";
    /** Used when the manifest names no icon or it cannot be read. */
    private static final String FALLBACK_ICON = "classpath:/img/icon.png";

    private static final Logger LOG = LoggerFactory.getLogger(WizardFrameController.class);

    private final InstallerModel model;
    private final WizardFlow flow;
    private final Stage stage;

    @FXML
    private ImageView icon;
    @FXML
    private Label pageTitle;
    @FXML
    private StackPane content;
    @FXML
    private Button backButton;
    @FXML
    private Button nextButton;
    @FXML
    private Button cancelButton;

    public WizardFrameController(InstallerModel model, WizardFlow flow, Stage stage) {
        this.model = Objects.requireNonNull(model, "model");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.stage = Objects.requireNonNull(stage, "stage");
    }

    /**
     * Loads the frame into the stage, starts the flow and returns the
     * controller. The stage is not shown; the caller decides when.
     */
    public static WizardFrameController show(Stage stage, InstallerModel model, WizardFlow flow) {
        URL url = WizardFrameController.class.getResource(FRAME_FXML);
        WizardFrameController controller = new WizardFrameController(model, flow, stage);
        FXMLLoader loader = I18nFxml.loader(url, model.i18n(), type -> controller);
        Parent root;
        try {
            root = I18nFxml.load(loader, model.i18n());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load the wizard frame", e);
        }

        WizardConfig wizard = model.getManifest().wizard();
        Scene scene = new Scene(root, wizard.width(), wizard.height());
        URL css = WizardFrameController.class.getResource(STYLESHEET);
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        stage.setMinWidth(wizard.width() * 0.75);
        stage.setMinHeight(wizard.height() * 0.75);
        stage.titleProperty().bind(model.i18n().text("window.title", model.getManifest().product().name()));
        controller.applyIcon();
        // The window's close button behaves exactly like "Cancel".
        stage.setOnCloseRequest(controller::onCloseRequest);
        controller.start();
        return controller;
    }

    public WizardFlow getFlow() {
        return flow;
    }

    @FXML
    private void initialize() {
        backButton.disableProperty().bind(Bindings.not(flow.canGoBackProperty()));
        nextButton.disableProperty().bind(Bindings.not(flow.canGoNextProperty()));
        nextButton.textProperty().bind(Bindings.createStringBinding(
                () -> model.i18n().get(nextKey(flow.nextActionProperty().get())),
                flow.nextActionProperty(), model.i18n().localeProperty()));
        nextButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);
        cancelButton.disableProperty().bind(model.cancelLockedProperty());

        flow.currentPageProperty().addListener((obs, old, page) -> showPage(page));
    }

    private void start() {
        flow.setOnFinish(this::finish);
        model.onPageRequest(flow::goBackTo);
        flow.start();
        showPage(flow.getCurrentPage());
    }

    private void showPage(WizardPage page) {
        if (page == null) {
            return;
        }
        pageTitle.textProperty().unbind();
        pageTitle.textProperty().bind(page.title());
        content.getChildren().setAll(page.content());
    }

    private static String nextKey(WizardPage.NextAction action) {
        return switch (action) {
            case INSTALL -> "wizard.install";
            case UNINSTALL -> "wizard.uninstall";
            case FINISH -> "wizard.finish";
            case NEXT -> "wizard.next";
        };
    }

    @FXML
    private void back() {
        flow.back();
    }

    @FXML
    private void next() {
        flow.next();
    }

    /**
     * Asks first. While the installation runs, the progress page takes the
     * cancel ({@link InstallerModel#requestCancel()}): the engine stops, the
     * rollback undoes what was done, and the window stays open to show it
     * Closing the window outright still goes through the model's
     * shutdown hooks (the {@code InstallTask} cancels and waits briefly).
     */
    @FXML
    private void cancel() {
        boolean installing = flow.backLockedProperty().get();
        String question = !installing ? "cancel.question"
                : model.isUninstalling() ? "cancel.question.uninstalling" : "cancel.question.installing";
        if (!CancelDialog.confirm(stage, model.i18n(), question)) {
            return;
        }
        LOG.info("Wizard cancelled on page {} (installing={})", flow.getCurrentPage().name(), installing);
        if (installing && model.requestCancel()) {
            LOG.info("Installation stops; the window stays open for the rollback");
            return;
        }
        close();
    }

    private void onCloseRequest(WindowEvent event) {
        event.consume();
        cancel();
    }

    private void finish() {
        LOG.info("Wizard finished; language={}, inputs={}",
                model.getLocale().toLanguageTag(), model.getInputs());
        if (model.isLaunchAfterFinish() && model.isInstallSucceeded()
                && model.getInstallMode() != InstallerModel.InstallMode.UNINSTALL) {
            launchApplication();
        }
        close();
    }

    /** "Launch now" on the finish page : the first shortcut's target, detached; a failure is only logged. */
    private void launchApplication() {
        java.nio.file.Path destination = model.getEffectiveDestination();
        java.util.Optional<java.nio.file.Path> target = LaunchTarget.of(model.getManifest(), model.getPlatform(), destination);
        if (target.isEmpty()) {
            LOG.warn("Nothing to launch: the manifest declares no usable shortcut");
            return;
        }
        try {
            AppLauncher.launchDetached(model.getPlatform(), target.get(), destination);
        } catch (IOException | RuntimeException e) {
            LOG.warn("{} could not be started: {}", target.get(), e.toString());
        }
    }

    /** Stops background work before the window goes away. */
    private void close() {
        model.shutdown();
        stage.setOnCloseRequest(null);
        stage.close();
    }

    /** Window and header icon from {@code product/icon}, falling back to the built-in one. */
    private void applyIcon() {
        String ref = model.getManifest().product().iconOrEmpty().orElse(FALLBACK_ICON);
        Image image = loadImage(ref);
        if (image == null && !ref.equals(FALLBACK_ICON)) {
            image = loadImage(FALLBACK_ICON);
        }
        if (image != null) {
            icon.setImage(image);
            stage.getIcons().setAll(image);
        }
    }

    private static Image loadImage(String ref) {
        try (InputStream in = ResourceRef.open(ref)) {
            Image image = new Image(in);
            if (image.isError()) {
                LOG.warn("Icon {} is not a readable image", ref);
                return null;
            }
            return image;
        } catch (IOException e) {
            LOG.warn("Icon {} could not be loaded: {}", ref, e.getMessage());
            return null;
        }
    }
}
