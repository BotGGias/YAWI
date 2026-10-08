package de.yawi.installer.ui.page;

import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.ComponentSelection;
import de.yawi.installer.ui.InstallerModel;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import javafx.fxml.FXML;
import de.yawi.installer.core.manifest.ByteSize;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Setup type page: presets, the component tree with check boxes, the
 * description of the highlighted component, sizes, and the inputs of the
 * selected components.
 *
 * <p>{@code model.getSelectedComponents()} is the single truth. Ticking runs
 * through {@link ComponentSelection#select}/{@link ComponentSelection#deselect}
 * and the result is mirrored back into every tree item, so dependencies get
 * ticked along and a locked component simply cannot be unticked.
 */
public class ComponentsPageController extends WizardPageController {

    private static final Logger LOG = LoggerFactory.getLogger(ComponentsPageController.class);

    /** Combo box entry for a selection that matches no preset. */
    static final String CUSTOM_PRESET = "";

    @FXML
    private HBox presetRow;
    @FXML
    private ComboBox<String> presets;
    @FXML
    private TreeView<Component> tree;
    @FXML
    private Label description;
    @FXML
    private Label lockHint;
    @FXML
    private Label installSize;
    @FXML
    private Label downloadSize;
    @FXML
    private Label spaceWarning;
    @FXML
    private Label optionsTitle;
    @FXML
    private VBox inputs;
    @FXML
    private CheckBox associate;
    @FXML
    private CheckBox addToPath;

    private final ComponentSelection selection;
    private final Map<String, CheckBoxTreeItem<Component>> items = new LinkedHashMap<>();
    private final BooleanProperty nextAllowed = new SimpleBooleanProperty(this, "nextAllowed", false);
    private InputForm form;
    /** Guards against the tree listeners firing while the tree is being mirrored from the model. */
    private boolean mirroring;
    private boolean initialised;

    public ComponentsPageController(InstallerModel model) {
        super(model);
        this.selection = new ComponentSelection(model.getManifest(), model.getPlatform().os());
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
        TreeItem<Component> root = new TreeItem<>();
        for (Component component : model.getManifest().components()) {
            // independent: a parent's check box must not tick the children
            // (there are none yet, but E05's tree is meant to grow).
            CheckBoxTreeItem<Component> item = new CheckBoxTreeItem<>(component, null, false, true);
            item.selectedProperty().addListener((obs, was, ticked) -> onTicked(component, ticked));
            items.put(component.id(), item);
            root.getChildren().add(item);
        }
        tree.setRoot(root);
        tree.setCellFactory(view -> new ComponentCell());
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> describe(item));
        describe(null);

        model.getSelectedComponents().addListener((SetChangeListener<String>) change -> mirror());
        mirror();

        initPresets();
        initSizes();

        form = new InputForm(model, inputs);
        initAssociate();
        initAddToPath();
        optionsTitle.visibleProperty().bind(inputs.visibleProperty());
        optionsTitle.managedProperty().bind(inputs.visibleProperty());
        // Something must be selected and every shown input must be valid.
        nextAllowed.bind(Bindings.isNotEmpty(model.getSelectedComponents()).and(form.validProperty()));
    }

    /**
     * The "associate file types" option: shown only when the manifest
     * declares associations that are on by default, pre-ticked from the manifest.
     */
    private void initAssociate() {
        boolean hasAssociations = !model.getManifest().integration().defaultAssociationExtensions().isEmpty();
        associate.setVisible(hasAssociations);
        associate.managedProperty().bind(associate.visibleProperty());
        if (hasAssociations) {
            associate.textProperty().bind(model.i18n().text("components.associate"));
            associate.selectedProperty().bindBidirectional(model.associateFileTypesProperty());
        }
    }

    /**
     * The "add to PATH" option: shown only when the manifest declares
     * PATH entries, pre-ticked. Editing shell profiles is invasive, so the user
     * can turn it off.
     */
    private void initAddToPath() {
        boolean hasPathEntries = !model.getManifest().integration().pathEntries().isEmpty();
        addToPath.setVisible(hasPathEntries);
        addToPath.managedProperty().bind(addToPath.visibleProperty());
        if (hasPathEntries) {
            addToPath.textProperty().bind(model.i18n().text("components.addToPath"));
            addToPath.selectedProperty().bindBidirectional(model.addPathEntriesProperty());
        }
    }

    /** The first visit starts from the manifest's proposal; later visits keep the user's choice. */
    @Override
    public void onEnter() {
        if (!initialised) {
            initialised = true;
            ObservableSet<String> selected = model.getSelectedComponents();
            // A preselection from outside (--components=) may be unresolved.
            Set<String> resolved = selected.isEmpty() ? selection.initialSelection() : selection.resolve(selected);
            selected.retainAll(resolved);
            selected.addAll(resolved);
        }
        if (tree.getSelectionModel().isEmpty() && !tree.getRoot().getChildren().isEmpty()) {
            tree.getSelectionModel().selectFirst();
        }
        // Repair keeps the recorded selection: lock the preset chooser too (the check boxes lock in the cell).
        presets.setDisable(repairing());
        tree.refresh();
    }

    private void onTicked(Component component, boolean ticked) {
        if (mirroring) {
            return;
        }
        ObservableSet<String> selected = model.getSelectedComponents();
        Set<String> next = ticked
                ? selection.select(component.id(), selected)
                : selection.deselect(component.id(), selected);
        if (!next.equals(selected)) {
            LOG.debug("Selection changed to {}", next);
            selected.retainAll(next);
            selected.addAll(next);
        } else {
            // Nothing changed (locked component): put the tick back.
            mirror();
        }
    }

    /** Ticks and locks every item according to the model; the preset box follows. */
    private void mirror() {
        mirroring = true;
        try {
            Set<String> selected = model.getSelectedComponents();
            items.forEach((id, item) -> item.setSelected(selected.contains(id)));
            tree.refresh();
            describe(tree.getSelectionModel().getSelectedItem());
            presets.setValue(selection.matchingPreset(selected).orElse(CUSTOM_PRESET));
        } finally {
            mirroring = false;
        }
    }

    // --- presets ---------------------------------------------------------

    /** Manifest presets plus "Custom"; the whole row disappears without a {@code <presets>} block. */
    private void initPresets() {
        List<String> ids = new ArrayList<>(model.getManifest().presets().stream().map(p -> p.id()).toList());
        boolean any = !ids.isEmpty();
        presetRow.setVisible(any);
        presetRow.setManaged(any);
        if (!any) {
            return;
        }
        ids.add(CUSTOM_PRESET);
        presets.getItems().setAll(ids);
        presets.setConverter(new StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : presetName(id);
            }

            @Override
            public String fromString(String text) {
                throw new UnsupportedOperationException("read-only combo box");
            }
        });
        // A language switch re-renders the entries; the value itself is an id.
        model.localeProperty().addListener((obs, old, locale) -> {
            String current = presets.getValue();
            presets.getItems().setAll(List.copyOf(presets.getItems()));
            presets.setValue(current);
        });
        presets.valueProperty().addListener((obs, old, id) -> onPresetChosen(id));
    }

    /** Bundle key {@code preset.<id>} if the installer knows the preset, else the manifest's {@code <i18n>}, else the id. */
    private String presetName(String id) {
        if (id.equals(CUSTOM_PRESET)) {
            return model.i18n().get("preset.custom");
        }
        String key = "preset." + id;
        String fallback = model.i18n().getMessages().hasKey(key) ? model.i18n().get(key) : id;
        return model.i18n().getManifestMessages().presetName(model.getLocale(), id, fallback);
    }

    private void onPresetChosen(String id) {
        if (mirroring || id == null || id.equals(CUSTOM_PRESET)) {
            return;
        }
        Set<String> next = selection.presetSelection(id);
        ObservableSet<String> selected = model.getSelectedComponents();
        if (!next.equals(selected)) {
            LOG.debug("Preset {} chosen: {}", id, next);
            selected.retainAll(next);
            selected.addAll(next);
        }
    }

    // --- sizes -----------------------------------------------------------

    private void initSizes() {
        ObservableSet<String> selected = model.getSelectedComponents();
        installSize.textProperty().bind(Bindings.createStringBinding(
                () -> model.i18n().get("components.size.install",
                        ByteSize.format(selection.installBytes(selected), model.getLocale())),
                selected, model.localeProperty()));

        // Only worth a line when download and install differ.
        StringBinding download = Bindings.createStringBinding(() -> {
            long install = selection.installBytes(selected);
            long bytes = selection.downloadBytes(selected);
            return bytes == 0 || bytes == install ? ""
                    : model.i18n().get("components.size.download", ByteSize.format(bytes, model.getLocale()));
        }, selected, model.localeProperty());
        downloadSize.textProperty().bind(download);
        downloadSize.visibleProperty().bind(download.isNotEmpty());
        downloadSize.managedProperty().bind(downloadSize.visibleProperty());

        StringBinding warning = Bindings.createStringBinding(() -> {
            long needed = selection.installBytes(selected);
            Path target = model.getEffectiveDestination();
            long usable;
            try {
                usable = model.getPlatform().usableSpace(target);
            } catch (IOException | RuntimeException e) {
                LOG.debug("Free space of {} unknown: {}", target, e.toString());
                return "";
            }
            return usable >= needed ? "" : model.i18n().get("components.size.warning",
                    ByteSize.format(usable, model.getLocale()), target.toString());
        }, selected, model.localeProperty(), model.destinationProperty(), model.systemWideProperty());
        spaceWarning.textProperty().bind(warning);
        spaceWarning.visibleProperty().bind(warning.isNotEmpty());
        spaceWarning.managedProperty().bind(spaceWarning.visibleProperty());
    }

    private void describe(TreeItem<Component> item) {
        description.textProperty().unbind();
        lockHint.textProperty().unbind();
        if (item == null || item.getValue() == null) {
            description.setText("");
            lockHint.setText("");
            return;
        }
        Component component = item.getValue();
        description.textProperty().bind(model.i18n().componentDescription(component));
        lockHint.textProperty().bind(lockReason(component, "components.lockHint.required",
                "components.lockHint.neededBy"));
    }

    /** "(required)" / "(needed by X, Y)" or empty, following the language and the selection. */
    private StringBinding lockReason(Component component, String requiredKey, String neededByKey) {
        return Bindings.createStringBinding(() -> {
            if (selection.isRequired(component.id())) {
                return model.i18n().get(requiredKey);
            }
            Set<String> dependents = selection.dependents(component.id(), model.getSelectedComponents());
            if (dependents.isEmpty()) {
                return "";
            }
            String names = dependents.stream()
                    .map(id -> model.getManifest().component(id).orElseThrow())
                    .map(c -> model.i18n().getManifestMessages().componentName(model.getLocale(), c))
                    .collect(Collectors.joining(", "));
            return model.i18n().get(neededByKey, names);
        }, model.getSelectedComponents(), model.localeProperty());
    }

    /**
     * A row: check box plus the translated name and, when locked, the reason
     * in brackets. The row stays clickable so the description can be shown
     * even for locked components; only the check box is disabled.
     */
    private final class ComponentCell extends TreeCell<Component> {
        private final CheckBox checkBox = new CheckBox();
        private CheckBoxTreeItem<Component> boundItem;

        ComponentCell() {
            checkBox.setMnemonicParsing(false);
        }

        @Override
        protected void updateItem(Component component, boolean empty) {
            super.updateItem(component, empty);
            textProperty().unbind();
            if (boundItem != null) {
                checkBox.selectedProperty().unbindBidirectional(boundItem.selectedProperty());
                boundItem = null;
            }
            if (empty || component == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            boundItem = (CheckBoxTreeItem<Component>) getTreeItem();
            checkBox.selectedProperty().bindBidirectional(boundItem.selectedProperty());
            // Repair keeps the recorded selection: the boxes are locked, but the inputs below stay editable.
            checkBox.setDisable(repairing() || selection.isLocked(component.id(), model.getSelectedComponents()));
            setGraphic(checkBox);
            StringBinding name = model.i18n().componentName(component);
            StringBinding reason = lockReason(component, "components.required", "components.neededBy");
            textProperty().bind(Bindings.createStringBinding(
                    () -> reason.get().isEmpty() ? name.get() : name.get() + " " + reason.get(),
                    name, reason));
        }
    }
}
