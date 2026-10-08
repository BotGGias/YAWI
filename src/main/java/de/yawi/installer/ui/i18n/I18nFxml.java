package de.yawi.installer.ui.i18n;

import javafx.beans.property.StringProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Accordion;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeTableView;
import javafx.util.Callback;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/**
 * The one place that creates {@link FXMLLoader}s: sets the controller
 * factory and the resource bundle, and after loading binds every
 * {@code %key} text to the current language.
 *
 * <p>An {@code FXMLLoader} resolves {@code %key} exactly once, at load time.
 * To make those texts follow a language change without reloading the page,
 * the loader gets {@link LocaleManager#markerBundle()}, which yields
 * {@code MARKER + key} instead of a translation. {@link #bindTexts} then
 * walks the scene graph, finds every text starting with the marker and binds
 * the property to {@link LocaleManager#text(String, Object...)}.
 *
 * <p>Supported: {@link Labeled#textProperty()} (Label, Button, CheckBox,
 * TitledPane, ...), {@code promptText} of text inputs and combo boxes, table
 * columns, tabs and tooltips. {@code FxmlContractTest} rejects {@code %key}
 * on anything else, so a marker can never stay visible.
 */
public final class I18nFxml {

    /** Prefix (NUL) of an unresolved text; never a legitimate first character of a label. */
    public static final char MARKER = (char) 0;

    private I18nFxml() {
    }

    public static FXMLLoader loader(URL fxml, LocaleManager i18n,
                                    Callback<Class<?>, Object> controllerFactory) {
        Objects.requireNonNull(fxml, "fxml");
        FXMLLoader loader = new FXMLLoader(fxml);
        loader.setResources(i18n.markerBundle());
        if (controllerFactory != null) {
            loader.setControllerFactory(controllerFactory);
        }
        return loader;
    }

    /** Loads the FXML and binds its texts; the root is returned. */
    public static <T> T load(FXMLLoader loader, LocaleManager i18n) throws IOException {
        T root = loader.load();
        if (root instanceof Node node) {
            bindTexts(node, i18n);
        }
        return root;
    }

    /** Binds every marked text below (and including) {@code root}. */
    public static void bindTexts(Node root, LocaleManager i18n) {
        if (root instanceof Labeled labeled) {
            bind(labeled.textProperty(), i18n);
        }
        if (root instanceof TextInputControl input) {
            bind(input.promptTextProperty(), i18n);
        }
        if (root instanceof ComboBoxBase<?> combo) {
            bind(combo.promptTextProperty(), i18n);
        }
        if (root instanceof Control control && control.getTooltip() != null) {
            bind(control.getTooltip().textProperty(), i18n);
        }
        if (root instanceof TableView<?> table) {
            table.getColumns().forEach(c -> bindColumn(c, i18n));
        }
        if (root instanceof TreeTableView<?> table) {
            table.getColumns().forEach(c -> bindColumn(c, i18n));
        }
        if (root instanceof TabPane tabs) {
            for (Tab tab : tabs.getTabs()) {
                bind(tab.textProperty(), i18n);
                if (tab.getContent() != null) {
                    bindTexts(tab.getContent(), i18n);
                }
            }
        }
        if (root instanceof Accordion accordion) {
            // Panes are children too, but walking them explicitly does not
            // depend on that; binding twice is harmless.
            for (TitledPane pane : accordion.getPanes()) {
                bindTexts(pane, i18n);
            }
        }
        if (root instanceof TitledPane pane && pane.getContent() != null) {
            bindTexts(pane.getContent(), i18n);
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                bindTexts(child, i18n);
            }
        }
    }

    private static void bindColumn(TableColumnBase<?, ?> column, LocaleManager i18n) {
        bind(column.textProperty(), i18n);
        column.getColumns().forEach(c -> bindColumn(c, i18n));
    }

    private static void bind(StringProperty property, LocaleManager i18n) {
        String value = property.get();
        if (isMarker(value) && !property.isBound()) {
            property.bind(i18n.text(value.substring(1)));
        }
    }

    /** Whether {@code text} is a still unbound {@code %key} placeholder. */
    public static boolean isMarker(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == MARKER;
    }
}
