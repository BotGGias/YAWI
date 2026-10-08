package de.yawi.installer.ui.i18n;

import de.yawi.installer.core.i18n.ManifestMessages;
import de.yawi.installer.core.i18n.Messages;
import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.ComponentInput;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * The current setup language as an observable property, plus text bindings
 * that follow it.
 *
 * <p>Convention for the whole UI: labels are <em>bound</em>, never set once.
 * Static texts come from {@code %key} in FXML and are bound automatically by
 * {@link I18nFxml}; texts with parameters are bound in the controller via
 * {@link #text(String, Object...)}. Either way a language change on the
 * welcome page updates everything on screen immediately.
 */
public final class LocaleManager {

    private final ObjectProperty<Locale> locale = new SimpleObjectProperty<>(this, "locale");
    private final Messages messages;
    private final ManifestMessages manifestMessages;
    private final ResourceBundle markerBundle = new MarkerBundle();

    public LocaleManager(Messages messages, ManifestMessages manifestMessages, Locale initial) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.manifestMessages = Objects.requireNonNull(manifestMessages, "manifestMessages");
        this.locale.set(Objects.requireNonNull(initial, "initial"));
    }

    public ObjectProperty<Locale> localeProperty() {
        return locale;
    }

    public Locale getLocale() {
        return locale.get();
    }

    public void setLocale(Locale value) {
        locale.set(Objects.requireNonNull(value, "locale"));
    }

    public Messages getMessages() {
        return messages;
    }

    public ManifestMessages getManifestMessages() {
        return manifestMessages;
    }

    /** One-shot text in the current language, for dialogs and log lines. */
    public String get(String key, Object... args) {
        return messages.get(locale.get(), key, args);
    }

    /** A text that re-evaluates whenever the language changes. */
    public StringBinding text(String key, Object... args) {
        Objects.requireNonNull(key, "key");
        return Bindings.createStringBinding(() -> messages.get(locale.get(), key, args), locale);
    }

    /** A manifest text ({@code <i18n>} path) that follows the language; see {@link ManifestMessages}. */
    public StringBinding manifestText(String path, String fallback) {
        Objects.requireNonNull(path, "path");
        return Bindings.createStringBinding(
                () -> manifestMessages.resolve(locale.get(), path, fallback), locale);
    }

    /** The step's translated name ({@code steps.<id>.name}), else its id. */
    public StringBinding stepName(String stepId) {
        return Bindings.createStringBinding(() -> manifestMessages.stepName(locale.get(), stepId), locale);
    }

    public StringBinding componentName(Component component) {
        return Bindings.createStringBinding(
                () -> manifestMessages.componentName(locale.get(), component), locale);
    }

    public StringBinding inputLabel(Component component, ComponentInput input) {
        return Bindings.createStringBinding(
                () -> manifestMessages.inputLabel(locale.get(), component, input), locale);
    }

    public StringBinding optionLabel(Component component, ComponentInput input, ComponentInput.Option option) {
        return Bindings.createStringBinding(
                () -> manifestMessages.optionLabel(locale.get(), component, input, option), locale);
    }

    /** Evaluates to {@code null} if the component has no description. */
    public StringBinding componentDescription(Component component) {
        return Bindings.createStringBinding(
                () -> manifestMessages.componentDescription(locale.get(), component), locale);
    }

    /**
     * The bundle to hand to an {@code FXMLLoader}. It does not translate:
     * every {@code %key} resolves to a marker that {@link I18nFxml#bindTexts}
     * turns into a live binding after loading. Unknown keys never throw; they
     * surface as {@code !key!} through {@link Messages}.
     */
    public ResourceBundle markerBundle() {
        return markerBundle;
    }

    private static final class MarkerBundle extends ResourceBundle {
        @Override
        protected Object handleGetObject(String key) {
            return I18nFxml.MARKER + key;
        }

        @Override
        public Enumeration<String> getKeys() {
            return Collections.emptyEnumeration();
        }

        @Override
        public boolean containsKey(String key) {
            return true;
        }
    }
}
