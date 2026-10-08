package de.yawi.installer.core.i18n;

import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.ComponentInput;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.Preset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the translatable manifest texts from the {@code <i18n>} block.
 *
 * <p>Texts are addressed by the {@code message/@path} the packager wrote,
 * e.g. {@code components.server.name}. Lookup order: the exact locale, then
 * the locale's language alone ({@code de-AT} finds {@code lang="de"}), then
 * the text written directly in the manifest. A missing translation is never
 * an error; the manifest text is the guaranteed fallback.
 *
 * <p>Headless; the JavaFX bindings live in {@code ui.i18n.LocaleManager}.
 */
public final class ManifestMessages {

    private static final Logger LOG = LoggerFactory.getLogger(ManifestMessages.class);

    private final Map<Locale, Map<String, String>> messages;

    public ManifestMessages(InstallManifest manifest) {
        this(manifest.messages(), knownPaths(manifest));
    }

    /**
     * @param messages   translated texts per language, keyed by path
     * @param knownPaths every path the manifest can address; paths outside it
     *                   are logged once so a typo does not go unnoticed
     */
    public ManifestMessages(Map<Locale, Map<String, String>> messages, Set<String> knownPaths) {
        this.messages = Objects.requireNonNull(messages, "messages");
        messages.forEach((locale, texts) -> texts.keySet().stream()
                .filter(path -> !knownPaths.contains(path))
                .sorted()
                .forEach(path -> LOG.warn("Manifest i18n ({}): path '{}' matches nothing, ignored",
                        locale.toLanguageTag(), path)));
    }

    /** The translated text for {@code path}, or {@code fallback} if there is none. */
    public String resolve(Locale locale, String path, String fallback) {
        Objects.requireNonNull(path, "path");
        if (locale != null) {
            String exact = lookup(locale, path);
            if (exact != null) {
                return exact;
            }
            if (!locale.getCountry().isEmpty() || !locale.getVariant().isEmpty()) {
                String byLanguage = lookup(Locale.forLanguageTag(locale.getLanguage()), path);
                if (byLanguage != null) {
                    return byLanguage;
                }
            }
        }
        return fallback;
    }

    public String componentName(Locale locale, Component component) {
        return resolve(locale, namePath(component.id()), component.name());
    }

    /** {@code null} if the component has no description at all. */
    public String componentDescription(Locale locale, Component component) {
        return resolve(locale, descriptionPath(component.id()), component.description());
    }

    public static String namePath(String componentId) {
        return "components." + componentId + ".name";
    }

    public static String presetPath(String presetId) {
        return "presets." + presetId + ".name";
    }

    public static String inputLabelPath(String componentId, String inputId) {
        return "components." + componentId + ".inputs." + inputId + ".label";
    }

    public static String optionLabelPath(String componentId, String inputId, String optionValue) {
        return "components." + componentId + ".inputs." + inputId + ".options." + optionValue;
    }

    public String inputLabel(Locale locale, Component component, ComponentInput input) {
        return resolve(locale, inputLabelPath(component.id(), input.id()), input.label());
    }

    public String optionLabel(Locale locale, Component component, ComponentInput input,
                              ComponentInput.Option option) {
        return resolve(locale, optionLabelPath(component.id(), input.id(), option.value()), option.label());
    }

    /** The translated preset name, or {@code fallback} (the bundle text or the id). */
    public String presetName(Locale locale, String presetId, String fallback) {
        return resolve(locale, presetPath(presetId), fallback);
    }

    public static String descriptionPath(String componentId) {
        return "components." + componentId + ".description";
    }

    public static String stepNamePath(String stepId) {
        return "steps." + stepId + ".name";
    }

    /** The translated step name for the progress page, or the id. */
    public String stepName(Locale locale, String stepId) {
        return resolve(locale, stepNamePath(stepId), stepId);
    }

    private String lookup(Locale locale, String path) {
        Map<String, String> texts = messages.get(locale);
        return texts == null ? null : texts.get(path);
    }

    private static Set<String> knownPaths(InstallManifest manifest) {
        Set<String> paths = new HashSet<>();
        for (Component c : manifest.components()) {
            paths.add(namePath(c.id()));
            paths.add(descriptionPath(c.id()));
            for (ComponentInput input : c.inputs()) {
                paths.add(inputLabelPath(c.id(), input.id()));
                for (ComponentInput.Option option : input.options()) {
                    paths.add(optionLabelPath(c.id(), input.id(), option.value()));
                }
            }
        }
        for (Preset p : manifest.presets()) {
            paths.add(presetPath(p.id()));
        }
        for (InstallStep s : manifest.steps()) {
            paths.add(stepNamePath(s.id()));
        }
        return paths;
    }
}
