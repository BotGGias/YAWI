package de.yawi.installer.ui.page;

import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.elevation.ElevationNeed;
import de.yawi.installer.core.elevation.ElevationPlanner;
import de.yawi.installer.core.engine.BundledArtifacts;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.ExecutionPlan;
import de.yawi.installer.core.engine.FailurePrompt;
import de.yawi.installer.core.engine.InstallRunner;
import de.yawi.installer.core.engine.Placeholders;
import de.yawi.installer.core.engine.ProgressListener;
import de.yawi.installer.core.engine.step.Steps;
import de.yawi.installer.core.manifest.ManifestException;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.manifest.ByteSize;
import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.ComponentSelection;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestOrigin;
import de.yawi.installer.core.manifest.Source;
import de.yawi.installer.ui.InstallerModel;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.fxml.FXML;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * "Ready to install": what will happen where, plus the manifest's origin
 * (the open remainder). Values are computed on every entry, so
 * going back and changing something is reflected; texts follow the language.
 */
public class SummaryPageController extends WizardPageController {

    private static final Logger LOG = LoggerFactory.getLogger(SummaryPageController.class);

    @FXML
    private Label destination;
    @FXML
    private Label components;
    @FXML
    private Label sources;
    @FXML
    private Label space;
    @FXML
    private Label manifest;
    @FXML
    private Label elevationCaption;
    @FXML
    private VBox elevationBox;
    @FXML
    private Label elevation;
    @FXML
    private Hyperlink changeDestination;
    @FXML
    private TitledPane elevatedCommands;
    @FXML
    private TextArea commandList;

    /** What the last {@link #onEnter} found out about administrator rights. */
    private ElevationNeed need = ElevationNeed.NONE;

    public SummaryPageController(InstallerModel model) {
        super(model);
    }

    @FXML
    private void initialize() {
        // Hidden rows take no space; the caption follows the box.
        elevationCaption.visibleProperty().bind(elevationBox.visibleProperty());
        elevationCaption.managedProperty().bind(elevationBox.visibleProperty());
        elevationBox.managedProperty().bind(elevationBox.visibleProperty());
        elevatedCommands.managedProperty().bind(elevatedCommands.visibleProperty());
        elevationBox.setVisible(false);
        elevatedCommands.setVisible(false);
    }

    /** The current assessment, for tests. */
    public ElevationNeed elevationNeed() {
        return need;
    }

    @FXML
    private void changeDestination() {
        model.requestPage("destination");
    }

    /** Nothing to choose while uninstalling. */
    @Override
    public boolean isSkippable() {
        return uninstalling();
    }

    @Override
    public NextAction nextAction() {
        return NextAction.INSTALL;
    }

    @Override
    public void onEnter() {
        InstallManifest m = model.getManifest();
        ComponentSelection selection = new ComponentSelection(m, model.getPlatform().os());
        Set<String> selected = selection.resolve(model.getSelectedComponents());
        List<Component> chosen = m.components().stream().filter(c -> selected.contains(c.id())).toList();
        long bytes = selection.installBytes(selected);
        List<Source> needed = m.sourcesFor(selected, model.getPlatform().os());
        ManifestOrigin origin = m.origin();

        bind(destination, constant(model.getEffectiveDestination().toString()));

        bind(components, chosen.isEmpty() ? none() : Bindings.createStringBinding(
                () -> chosen.stream()
                        .map(c -> model.i18n().getManifestMessages().componentName(model.getLocale(), c))
                        .collect(Collectors.joining(", ")),
                model.localeProperty()));
        // "game-data (shipped copy), extras (download, not verifiable)": the way each source is obtained
        // follows the language; a download without sha256 in the manifest says so.
        bind(sources, needed.isEmpty() ? none() : Bindings.createStringBinding(
                () -> needed.stream()
                        .map(s -> s.id() + " (" + sourceDescription(s) + ")")
                        .collect(Collectors.joining(", ")),
                model.localeProperty()));
        bind(space, Bindings.createStringBinding(
                () -> ByteSize.format(bytes, model.getLocale()), model.localeProperty()));
        // "CLASSPATH (/installer.xml) - not signed": the signature status follows the language.
        bind(manifest, model.i18n().text(origin.signature() == ManifestOrigin.Signature.VERIFIED
                ? "summary.manifest.signed" : "summary.manifest.unsigned", origin.kind() + " (" + origin.location() + ")"));
        showElevation(m, selected);
    }

    /**
     * Administrator rights: whether the destination or
     * marked steps need them, and which commands would run that way, in clear
     * text with secrets masked. A plan that cannot be built is the
     * progress page's business; nothing is shown then.
     */
    private void showElevation(InstallManifest m, Set<String> selected) {
        need = ElevationNeed.NONE;
        List<String> commands = List.of();
        Path destination = model.getEffectiveDestination();
        try {
            Map<String, String> inputs = InstallRunner.selectedInputs(m, selected, model.getInputs());
            Map<String, String> masked = ElevationPlanner.maskSecrets(m, inputs);
            Placeholders placeholders = Placeholders.of(m, model.getPlatform(), destination, masked, selected);
            ExecutionPlan plan = ExecutionPlan.build(m, selected, model.getPlatform(), placeholders, Steps.DEFAULT,
                    model.enabledAssociations(), model.isAddPathEntries());
            need = ElevationPlanner.assess(plan, model.isElevationRequired(), model.getPlatform().isElevated());
            if (need.required()) {
                ExecutionContext context = new ExecutionContext(m, model.getPlatform(), destination, masked, selected,
                        placeholders, new BundledArtifacts(), new InstallationRecord(m.product().id(),
                        m.product().version(), Instant.now(), destination, selected, Map.of()),
                        ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
                commands = ElevationPlanner.describeCommands(plan, need.elevatedStepIds(), context);
            }
        } catch (ManifestException e) {
            LOG.debug("No elevation assessment: {}", e.getMessage());
        }
        elevationBox.setVisible(need.required());
        elevatedCommands.setVisible(need.required() && !commands.isEmpty());
        commandList.setText(String.join("\n", commands));
        elevatedCommands.setExpanded(false);
        if (need.mode() == ElevationNeed.Mode.WHOLE) {
            bind(elevation, model.i18n().text("summary.elevation.destination", destination.toString()));
        } else if (need.mode() == ElevationNeed.Mode.PARTIAL) {
            bind(elevation, model.i18n().text("summary.elevation.steps", need.elevatedStepIds().size()));
        }
        // Changing the destination only helps when it is the destination that needs the rights.
        changeDestination.setVisible(need.mode() == ElevationNeed.Mode.WHOLE);
        changeDestination.setManaged(need.mode() == ElevationNeed.Mode.WHOLE);
    }

    private String sourceDescription(Source source) {
        ProviderKind choice = model.sourceChoice(source);
        String text = model.i18n().get("summary.source." + choice.name().toLowerCase(java.util.Locale.ROOT));
        if (choice != ProviderKind.BUNDLED && source.sha256() == null) {
            text += ", " + model.i18n().get("summary.source.unverified");
        }
        return text;
    }

    private StringBinding none() {
        return model.i18n().text("summary.none");
    }

    private static StringBinding constant(String text) {
        return Bindings.createStringBinding(() -> text);
    }

    private static void bind(Label label, StringBinding value) {
        label.textProperty().unbind();
        label.textProperty().bind(value);
    }
}
