package de.yawi.installer.core.i18n;

import de.yawi.installer.core.manifest.TestManifests;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every language the bundled manifest offers must have a bundle file, and the
 * complete ones (English, German) must define exactly the base key set.
 *
 * <p>For the other languages missing keys are only reported on stderr: no
 * translations exist yet and the English fallback covers them. Move those
 * languages into {@link #COMPLETE} once they are translated; from the first
 * release on every offered language belongs there.
 */
class BundleCompletenessTest {

    /** Languages that must be fully translated. */
    private static final Set<String> COMPLETE = Set.of("en", "de");

    private final Messages messages = Messages.load();

    @Test
    void everyOfferedLanguageHasABundleFile() {
        List<String> missing = new ArrayList<>();
        for (Locale locale : TestManifests.bundled().languages().languages()) {
            if (!messages.hasBundle(locale)) {
                missing.add(locale.toLanguageTag());
            }
        }
        assertTrue(missing.isEmpty(), "no bundle file for: " + missing);
    }

    @Test
    void completeLanguagesDefineExactlyTheBaseKeys() {
        List<String> problems = new ArrayList<>();
        for (String lang : COMPLETE) {
            Locale locale = Locale.forLanguageTag(lang);
            if (lang.equals("en")) {
                continue; // the base file itself
            }
            Set<String> missing = messages.missingKeys(locale);
            if (!missing.isEmpty()) {
                problems.add(lang + " is missing " + missing);
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void noBundleDefinesKeysUnknownToTheBaseFile() {
        List<String> problems = new ArrayList<>();
        for (Locale locale : TestManifests.bundled().languages().languages()) {
            Set<String> extra = messages.extraKeys(locale);
            if (!extra.isEmpty()) {
                problems.add(locale.toLanguageTag() + " defines unknown keys " + extra);
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void noBaseValueIsEmpty() {
        List<String> empty = messages.keys().stream()
                .filter(k -> messages.get(Locale.ENGLISH, k).isBlank())
                .sorted()
                .toList();
        assertEquals(List.of(), empty, "empty base texts");
    }

    @Test
    void incompleteLanguagesAreReported() {
        for (Locale locale : TestManifests.bundled().languages().languages()) {
            if (COMPLETE.contains(locale.getLanguage())) {
                continue;
            }
            Set<String> missing = messages.missingKeys(locale);
            if (!missing.isEmpty()) {
                // Warning only until the first release, see class comment.
                System.err.printf("WARNING: bundle %s is missing %d of %d keys: %s%n",
                        locale.toLanguageTag(), missing.size(), messages.keys().size(), missing);
            }
        }
    }
}
