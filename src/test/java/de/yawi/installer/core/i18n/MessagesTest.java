package de.yawi.installer.core.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    private static final String TEST_BASE = "/i18n-test/messages";

    private static Messages messages() {
        return Messages.load(TEST_BASE);
    }

    @Test
    void readsCyrillicAndCjkAsUtf8() {
        Messages m = messages();
        assertEquals("Привет", m.get(Locale.forLanguageTag("ru"), "greeting"));
        assertEquals("こんにちは", m.get(Locale.JAPANESE, "greeting"));
        assertEquals("さようなら", m.get(Locale.JAPANESE, "farewell"));
    }

    @Test
    void fallsBackToEnglishForMissingKey() {
        // ru has no 'farewell'
        assertEquals("Goodbye", messages().get(Locale.forLanguageTag("ru"), "farewell"));
    }

    @Test
    void fallsBackToEnglishForUnknownLanguage() {
        Messages m = messages();
        assertEquals("Hello", m.get(Locale.forLanguageTag("xx"), "greeting"));
        assertFalse(m.hasBundle(Locale.forLanguageTag("xx")));
        assertTrue(m.hasBundle(Locale.GERMAN));
        // English is the base file itself.
        assertTrue(m.hasBundle(Locale.ENGLISH));
        assertTrue(m.hasBundle(Locale.US));
    }

    @Test
    void nullOrRootLocaleUsesEnglish() {
        Messages m = messages();
        assertEquals("Hello", m.get(null, "greeting"));
        assertEquals("Hello", m.get(Locale.ROOT, "greeting"));
    }

    @Test
    void unknownKeyNeverThrows() {
        assertEquals("!no.such.key!", messages().get(Locale.GERMAN, "no.such.key"));
    }

    @Test
    void substitutesArguments() {
        Messages m = messages();
        assertEquals("Welcome to SU, version 1.2", m.get(Locale.ENGLISH, "welcome", "SU", "1.2"));
        assertEquals("Willkommen bei SU, Version 1.2", m.get(Locale.GERMAN, "welcome", "SU", "1.2"));
        assertEquals("SU へようこそ、バージョン 1.2", m.get(Locale.JAPANESE, "welcome", "SU", "1.2"));
    }

    @Test
    void formatsNumbersInTheGivenLocale() {
        Messages m = messages();
        assertEquals("You selected 1,234 components", m.get(Locale.ENGLISH, "count", 1234));
        assertEquals("1.234 Komponenten gewählt", m.get(Locale.GERMAN, "count", 1234));
    }

    @Test
    void doubledApostropheOnlyMattersWithArguments() {
        Messages m = messages();
        assertEquals("Don't stop now", m.get(Locale.ENGLISH, "quoted", "now"));
        // Without arguments the raw text is returned, so plain texts need no escaping.
        assertEquals("Don''t stop {0}", m.get(Locale.ENGLISH, "quoted"));
    }

    @Test
    void regionalBundleWinsOverLanguageBundle() {
        Messages m = messages();
        Locale austrian = Locale.forLanguageTag("de-AT");
        assertEquals("Servus", m.get(austrian, "greeting"));
        // de_AT only defines 'greeting'; everything else comes from English, not from de.
        assertEquals("Goodbye", m.get(austrian, "farewell"));
        assertEquals("Tschüss", m.get(Locale.forLanguageTag("de-DE"), "farewell"));
    }

    @Test
    void reportsMissingAndExtraKeys() {
        Messages m = messages();
        assertEquals(Set.of("farewell", "only.in.base"), m.missingKeys(Locale.forLanguageTag("ru")));
        assertEquals(Set.of("only.in.base"), m.missingKeys(Locale.JAPANESE));
        assertEquals(Set.of("extra.key"), m.extraKeys(Locale.JAPANESE));
        assertEquals(Set.of(), m.missingKeys(Locale.GERMAN));
        assertEquals(Set.of(), m.missingKeys(Locale.ENGLISH));
        // No file at all: everything is missing.
        assertEquals(m.keys(), m.missingKeys(Locale.forLanguageTag("xx")));
    }

    @Test
    void keysComeFromTheBaseFile() {
        Messages m = messages();
        assertEquals(Set.of("greeting", "farewell", "welcome", "count", "quoted", "only.in.base"), m.keys());
        assertTrue(m.hasKey("greeting"));
        assertFalse(m.hasKey("extra.key"));
    }

    @Test
    void missingBaseFileIsAPackagingDefect() {
        assertThrows(IllegalStateException.class, () -> Messages.load("/i18n-test/nope"));
    }

    @Test
    void installerBundleLoads() {
        Messages m = Messages.load();
        assertEquals("Next", m.get(Locale.ENGLISH, "wizard.next"));
        assertEquals("Weiter", m.get(Locale.GERMAN, "wizard.next"));
        assertEquals("YOUR INSTALLER Setup", m.get(Locale.GERMAN, "window.title", "YOUR INSTALLER"));
    }
}
