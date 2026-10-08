package de.yawi.installer.ui.page;

import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.ComponentInput;
import de.yawi.installer.core.manifest.ComponentInput.Option;
import de.yawi.installer.core.manifest.InputValidator;
import de.yawi.installer.ui.InstallerModel;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.SetChangeListener;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The form for the {@code <input>} elements of the selected components,
 * rebuilt whenever the selection changes.
 *
 * <p>Every value lives in {@code model.getInputs()} the moment its field
 * exists — defaults included — so the steps always find
 * {@code ${input.<id>}} for a selected component. Values of deselected
 * components stay in the map but are not validated and not shown.
 * A violation marks the field ({@code invalid} style class), shows the
 * translated reason below it and clears {@link #validProperty()}.
 */
final class InputForm {

    private static final Logger LOG = LoggerFactory.getLogger(InputForm.class);

    static final String INVALID_CLASS = "invalid";
    static final String ERROR_CLASS = "input-error";

    private final InstallerModel model;
    private final VBox container;
    private final ReadOnlyBooleanWrapper valid = new ReadOnlyBooleanWrapper(this, "valid", true);
    private final List<Field> fields = new ArrayList<>();
    /** Re-renders the choice boxes of the current fields; one listener for the form's lifetime. */
    private final List<Runnable> localeRefreshers = new ArrayList<>();

    InputForm(InstallerModel model, VBox container) {
        this.model = model;
        this.container = container;
        model.getSelectedComponents().addListener((SetChangeListener<String>) change -> rebuild());
        model.localeProperty().addListener((obs, old, locale) -> localeRefreshers.forEach(Runnable::run));
        rebuild();
    }

    /** True while every shown field holds a valid value. */
    ReadOnlyBooleanProperty validProperty() {
        return valid.getReadOnlyProperty();
    }

    /** One block per selected component that declares inputs, manifest order. */
    void rebuild() {
        container.getChildren().clear();
        fields.clear();
        localeRefreshers.clear();
        for (Component component : model.getManifest().components()) {
            if (component.inputs().isEmpty() || !model.getSelectedComponents().contains(component.id())) {
                continue;
            }
            Label heading = new Label();
            heading.getStyleClass().add("section-title");
            heading.textProperty().bind(model.i18n().componentName(component));

            GridPane grid = new GridPane();
            grid.setHgap(12);
            grid.setVgap(4);
            int row = 0;
            for (ComponentInput input : component.inputs()) {
                Field field = new Field(component, input);
                fields.add(field);
                grid.add(field.label, 0, row);
                grid.add(field.control, 1, row);
                grid.add(field.error, 1, row + 1);
                row += 2;
            }
            container.getChildren().addAll(heading, grid);
        }
        container.setVisible(!fields.isEmpty());
        container.setManaged(!fields.isEmpty());
        revalidate();
    }

    private void revalidate() {
        boolean allValid = true;
        for (Field field : fields) {
            allValid &= field.validate();
        }
        valid.set(allValid);
    }

    /** Label, control and error line of one input; keeps the model in step with the control. */
    private final class Field {
        final ComponentInput input;
        final Label label = new Label();
        final Node control;
        final Label error = new Label();
        private final SimpleObjectProperty<String> value = new SimpleObjectProperty<>();

        Field(Component component, ComponentInput input) {
            this.input = input;
            label.textProperty().bind(model.i18n().inputLabel(component, input));
            error.getStyleClass().add(ERROR_CLASS);
            error.setWrapText(true);
            error.setVisible(false);
            error.managedProperty().bind(error.visibleProperty());

            String initial = model.getInputs().getOrDefault(input.id(), InputValidator.defaultValue(input));
            control = createControl(component, input, initial);
            control.setId("input-" + input.id());
            store(initial);
            value.addListener((obs, old, current) -> {
                store(current);
                revalidate();
            });
        }

        private Node createControl(Component component, ComponentInput input, String initial) {
            switch (input.type()) {
                case BOOL -> {
                    CheckBox box = new CheckBox();
                    box.setSelected(Boolean.parseBoolean(initial));
                    box.selectedProperty().addListener((obs, old, on) -> value.set(Boolean.toString(on)));
                    return box;
                }
                case CHOICE -> {
                    ComboBox<Option> combo = new ComboBox<>();
                    combo.getItems().setAll(input.options());
                    combo.setConverter(new StringConverter<>() {
                        @Override
                        public String toString(Option option) {
                            return option == null ? "" : model.i18n().getManifestMessages()
                                    .optionLabel(model.getLocale(), component, input, option);
                        }

                        @Override
                        public Option fromString(String text) {
                            throw new UnsupportedOperationException("read-only combo box");
                        }
                    });
                    // Re-render the labels on a language switch; the value is the option itself.
                    localeRefreshers.add(() -> {
                        Option current = combo.getValue();
                        combo.getItems().setAll(input.options());
                        combo.setValue(current);
                    });
                    input.option(initial).ifPresent(combo::setValue);
                    combo.valueProperty().addListener((obs, old, option) ->
                            value.set(option == null ? "" : option.value()));
                    return combo;
                }
                default -> {
                    TextField text = new TextField(initial);
                    if (input.type() == ComponentInput.InputType.INT
                            && (input.min() != null || input.max() != null)) {
                        text.setPromptText(Optional.ofNullable(input.min()).map(String::valueOf).orElse("")
                                + " – " + Optional.ofNullable(input.max()).map(String::valueOf).orElse(""));
                    }
                    text.textProperty().addListener((obs, old, current) -> value.set(current));
                    return text;
                }
            }
        }

        private void store(String text) {
            String stored = text == null ? "" : text.trim();
            if (!stored.equals(model.getInputs().get(input.id()))) {
                model.getInputs().put(input.id(), stored);
                LOG.debug("Input {} = '{}'", input.id(), stored);
            }
        }

        /** Marks the field and returns whether it is valid. */
        boolean validate() {
            Optional<InputValidator.Problem> problem =
                    InputValidator.validate(input, model.getInputs().get(input.id()));
            control.getStyleClass().remove(INVALID_CLASS);
            error.textProperty().unbind();
            if (problem.isEmpty()) {
                error.setVisible(false);
                return true;
            }
            control.getStyleClass().add(INVALID_CLASS);
            error.textProperty().bind(model.i18n().text(problem.get().key(), problem.get().args()));
            error.setVisible(true);
            return false;
        }
    }
}
