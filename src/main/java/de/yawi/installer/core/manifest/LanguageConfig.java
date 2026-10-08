package de.yawi.installer.core.manifest;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The {@code <languages>} block: the languages the wizard offers and the one
 * used when the system language is not among them.
 */
public record LanguageConfig(Locale defaultLanguage, List<Locale> languages) {

    public LanguageConfig {
        Objects.requireNonNull(defaultLanguage, "defaultLanguage");
        languages = List.copyOf(languages);
    }

    /** The offered language matching {@code locale}'s language, else the default. */
    public Locale resolve(Locale locale) {
        if (locale != null) {
            for (Locale candidate : languages) {
                if (candidate.getLanguage().equals(locale.getLanguage())) {
                    return candidate;
                }
            }
        }
        return defaultLanguage;
    }
}
