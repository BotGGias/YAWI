package de.yawi.installer.ui.page;

import de.yawi.installer.core.manifest.ByteSize;
import de.yawi.installer.core.manifest.ComponentSelection;
import de.yawi.installer.core.manifest.DestinationConfig;
import de.yawi.installer.core.platform.DestinationValidator;
import de.yawi.installer.core.platform.DestinationValidator.Check;
import de.yawi.installer.core.platform.DestinationValidator.Parsed;
import de.yawi.installer.core.platform.DestinationValidator.Problem;
import de.yawi.installer.ui.InstallerModel;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Destination page: the install folder, proposed from the manifest and the
 * platform, editable by hand or through a {@link DirectoryChooser}, and
 * checked by the {@link DestinationValidator}.
 *
 * <p>Every parsed path goes straight into {@code model.setDestination}, so
 * {@code ${destination}} is set from the first visit on; what the user typed
 * survives going back and forth. Variables in the text ({@code ~},
 * {@code $HOME}, {@code %LOCALAPPDATA%}) are expanded like in the manifest.
 *
 * <p>The file system probes run on a background thread, debounced while the
 * user types; the result is shown on the page (never in a dialog) and
 * "Next" waits for it. A folder that already holds files needs a tick in
 * the confirmation box.
 *
 * <p>"For all users" switches the text to the system-wide default and sets
 * {@code model.systemWide}; whether the current folder actually needs
 * elevated rights is a property of the folder, not of the switch, so it is
 * taken from the check ({@code model.elevationRequired}) and shown for
 * hand-typed system paths too.
 */
public class DestinationPageController extends WizardPageController {

    private static final Logger LOG = LoggerFactory.getLogger(DestinationPageController.class);

    /** How long typing may pause before the file system is asked. */
    static final Duration DEBOUNCE = Duration.millis(300);

    @FXML
    private Label hint;
    @FXML
    private ToggleGroup scope;
    @FXML
    private RadioButton scopeUser;
    @FXML
    private RadioButton scopeAll;
    @FXML
    private TextField path;
    @FXML
    private Button browse;
    @FXML
    private Label space;
    @FXML
    private VBox messages;
    @FXML
    private CheckBox confirmNotEmpty;
    @FXML
    private Label elevationHint;

    private final DestinationValidator validator;
    private final ComponentSelection selection;
    private final ExecutorService checker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "destination-check");
        t.setDaemon(true);
        return t;
    });
    private final PauseTransition debounce = new PauseTransition(DEBOUNCE);
    /** The latest applied result; {@code null} while a check is pending. */
    private final ObjectProperty<Check> lastCheck = new SimpleObjectProperty<>(this, "lastCheck");
    private final BooleanProperty nextAllowed = new SimpleBooleanProperty(this, "nextAllowed", false);
    /** Grows with every started check; a result is applied only if it is still the latest. */
    private int sequence;
    private CompletableFuture<Check> pending = CompletableFuture.completedFuture(null);
    private boolean initialised;
    /** The last path this page put into the model; a different model value came from outside. */
    private Path pushed;
    /** Where the chooser opens next time: the folder picked last, else the current path. */
    private File lastChosen;

    public DestinationPageController(InstallerModel model) {
        super(model);
        this.validator = new DestinationValidator(model.getPlatform());
        this.selection = new ComponentSelection(model.getManifest(), model.getPlatform().os());
        model.onShutdown(checker::shutdownNow);
    }

    /** Nothing to choose while uninstalling. */
    @Override
    public boolean isSkippable() {
        return uninstalling();
    }

    @Override
    public ObservableBooleanValue nextAllowed() {
        return nextAllowed;
    }

    @FXML
    private void initialize() {
        hint.textProperty().bind(model.i18n().text("destination.hint", model.getManifest().product().name()));

        DestinationConfig config = model.getManifest().destination();
        if (!config.allowUserChange()) {
            // Stays readable, so the user sees where the packager put it.
            path.setEditable(false);
            browse.setDisable(true);
        }

        initScope();

        confirmNotEmpty.setVisible(false);
        confirmNotEmpty.managedProperty().bind(confirmNotEmpty.visibleProperty());
        elevationHint.visibleProperty().bind(model.elevationRequiredProperty());
        elevationHint.managedProperty().bind(elevationHint.visibleProperty());
        space.managedProperty().bind(space.visibleProperty());
        space.setVisible(false);

        debounce.setOnFinished(e -> checkNow());
        path.textProperty().addListener((obs, old, text) -> onPathChanged(text));

        nextAllowed.bind(Bindings.createBooleanBinding(() -> {
            Check check = lastCheck.get();
            return check != null && !check.isBlocking()
                    && (!check.needsConfirmation() || confirmNotEmpty.isSelected());
        }, lastCheck, confirmNotEmpty.selectedProperty()));
    }

    /**
     * The first visit proposes the manifest's/platform's default; later
     * visits keep the text but check again, since the selection - and with
     * it the required space - may have changed.
     */
    @Override
    public void onEnter() {
        if (!initialised || !Objects.equals(model.getDestination(), pushed)) {
            // First visit, or the maintenance page prefilled the model.
            initialised = true;
            // The scope listener only rewrites the text when the model disagrees, so this keeps the path.
            scope.selectToggle(model.isSystemWide() ? scopeAll : scopeUser);
            path.setText(model.getEffectiveDestination().toString());
        }
        checkNow();
    }

    /**
     * The scope switch. It stays usable with {@code allowUserChange="false"}:
     * it only moves between the two folders the packager defined. An
     * already elevated process needs no rights hint on the label.
     */
    private void initScope() {
        if (model.getPlatform().isElevated()) {
            scopeAll.textProperty().unbind();
            scopeAll.textProperty().bind(model.i18n().text("destination.scope.all.elevated"));
        }
        scope.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                scope.selectToggle(old); // one of the two is always chosen
                return;
            }
            boolean systemWide = toggle == scopeAll;
            if (systemWide != model.isSystemWide()) {
                LOG.debug("Scope switched to {}", systemWide ? "all users" : "current user");
                model.setSystemWide(systemWide);
                path.setText(model.defaultDestination(systemWide).toString());
            }
        });
    }

    private void onPathChanged(String text) {
        Parsed parsed = validator.parse(text);
        if (parsed.isValid()) {
            pushed = parsed.path();
            if (!parsed.path().equals(model.getDestination())) {
                LOG.debug("Destination set to {}", parsed.path());
                model.setDestination(parsed.path());
            }
        }
        // A new folder needs a new confirmation.
        confirmNotEmpty.setSelected(false);
        lastCheck.set(null);
        debounce.playFromStart();
    }

    /**
     * Runs the check right away instead of after the typing pause. The
     * future completes on the JavaFX thread once the result is on the page;
     * it is cancelled if a newer check overtakes it. Public for the tests.
     */
    public CompletableFuture<Check> checkNow() {
        debounce.stop();
        int seq = ++sequence;
        pending.cancel(false);
        String text = path.getText();
        long required = selection.installBytes(model.getSelectedComponents());
        long minFree = model.getManifest().destination().minFreeBytes();
        lastCheck.set(null);

        CompletableFuture<Check> future = new CompletableFuture<>();
        pending = future;
        CompletableFuture.supplyAsync(() -> validator.check(text, required, minFree), checker)
                .whenCompleteAsync((check, failure) -> {
                    if (seq != sequence) {
                        return; // overtaken
                    }
                    if (failure != null) {
                        LOG.warn("Destination check failed", failure);
                        future.completeExceptionally(failure);
                        return;
                    }
                    show(check);
                    future.complete(check);
                }, javafx.application.Platform::runLater);
        return future;
    }

    /** Puts a result on the page: space line, one label per finding, the confirmation box. */
    private void show(Check check) {
        messages.getChildren().clear();
        for (Problem problem : check.problems()) {
            Label label = new Label();
            label.setWrapText(true);
            label.getStyleClass().add(problem.isError() ? InputForm.ERROR_CLASS : "warning");
            label.textProperty().bind(translated(problem.key(), problem.args()));
            messages.getChildren().add(label);
        }
        boolean invalid = check.isBlocking();
        path.getStyleClass().remove(InputForm.INVALID_CLASS);
        if (invalid) {
            path.getStyleClass().add(InputForm.INVALID_CLASS);
        }

        space.textProperty().unbind();
        space.setVisible(check.usableBytes() >= 0);
        if (check.usableBytes() >= 0) {
            long required = selection.installBytes(model.getSelectedComponents());
            space.textProperty().bind(translated("destination.space", check.usableBytes(), required));
        }
        confirmNotEmpty.setVisible(check.needsConfirmation());
        // Modifying or updating a registered installation: that folder is expected to be full.
        boolean known = check.path() != null && model.getExistingInstallation()
                .map(existing -> existing.destination().equals(check.path().toAbsolutePath().normalize()))
                .orElse(false);
        confirmNotEmpty.setSelected(check.needsConfirmation() && known);
        model.setElevationRequired(check.elevationRequired());
        lastCheck.set(check);
    }

    /** The text for a finding, following the language; byte counts are formatted per locale. */
    private StringBinding translated(String key, Object... args) {
        return Bindings.createStringBinding(() -> {
            Locale locale = model.getLocale();
            Object[] formatted = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                formatted[i] = args[i] instanceof Long bytes ? ByteSize.format(bytes, locale) : args[i];
            }
            return model.i18n().get(key, formatted);
        }, model.localeProperty());
    }

    @FXML
    private void browse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.titleProperty().bind(model.i18n().text("page.destination.title"));
        File start = lastChosen != null && lastChosen.isDirectory()
                ? lastChosen : nearestExistingDir(validator.parse(path.getText()).path());
        if (start != null) {
            chooser.setInitialDirectory(start);
        }
        File chosen = chooser.showDialog(path.getScene().getWindow());
        if (chosen != null) {
            lastChosen = chosen;
            path.setText(chosen.getAbsolutePath());
        }
    }

    /** {@code path} if it is a directory, else the closest ancestor that is one; null for no path. */
    private static File nearestExistingDir(Path path) {
        Path current = path;
        while (current != null && !Files.isDirectory(current)) {
            current = current.getParent();
        }
        return current == null ? null : current.toFile();
    }
}
