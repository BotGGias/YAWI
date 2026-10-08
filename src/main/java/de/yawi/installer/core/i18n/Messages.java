package de.yawi.installer.core.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The installer's own user-facing texts, loaded from {@code .properties} files
 * on the classpath.
 *
 * <p>The base file ({@code messages.properties}, English) is the reference:
 * it defines the key set. A language file ({@code messages_de.properties}) may
 * cover any subset; whatever it lacks falls back to English, and a key nobody
 * defines comes back as {@code !key!} together with a warning. The UI never
 * sees a {@code MissingResourceException}.
 *
 * <p>Files are read as UTF-8 regardless of the JVM default. This class does
 * the loading itself instead of going through {@link java.util.ResourceBundle}:
 * the bundle parent chain falls back silently, so the required warning about
 * missing keys could not be logged, and the {@code Control} overloads that
 * would allow it are not available inside a named module.
 *
 * <p>Headless; the CLI uses it as well.
 */
public final class Messages {

    /** Classpath base of the installer's bundles, without {@code .properties}. */
    public static final String DEFAULT_BASE = "/de/yawi/installer/i18n/messages";

    /** The language of the base file; it has no {@code messages_en} file of its own. */
    public static final String BASE_LANGUAGE = "en";

    private static final Logger LOG = LoggerFactory.getLogger(Messages.class);

    private final String basePath;
    private final Map<String, String> base;
    private final Map<Locale, Map<String, String>> loaded = new ConcurrentHashMap<>();
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

    private Messages(String basePath, Map<String, String> base) {
        this.basePath = basePath;
        this.base = base;
    }

    /** Loads the installer's bundles from {@link #DEFAULT_BASE}. */
    public static Messages load() {
        return load(DEFAULT_BASE);
    }

    /**
     * Loads bundles from another base, e.g. {@code /i18n-test/messages}.
     *
     * @throws IllegalStateException if the base file does not exist; that is a
     *         packaging defect, not a runtime condition
     */
    public static Messages load(String basePath) {
        Objects.requireNonNull(basePath, "basePath");
        Map<String, String> base = read(basePath + ".properties");
        if (base == null) {
            throw new IllegalStateException("Message bundle not found: " + basePath + ".properties");
        }
        return new Messages(basePath, base);
    }

    /**
     * The text for {@code key} in {@code locale}, falling back to English and
     * finally to {@code !key!}. Arguments are substituted with
     * {@link MessageFormat} in the given locale; without arguments the text is
     * returned verbatim, so a plain apostrophe only needs doubling in texts
     * that carry {@code {n}} placeholders.
     */
    public String get(Locale locale, String key, Object... args) {
        Objects.requireNonNull(key, "key");
        Map<String, String> bundle = bundleFor(locale);
        String text = bundle.get(key);
        if (text == null) {
            text = base.get(key);
        }
        if (text == null) {
            if (warnedKeys.add(key)) {
                LOG.warn("Message key '{}' is not defined in {}.properties", key, basePath);
            }
            return "!" + key + "!";
        }
        if (args == null || args.length == 0) {
            return text;
        }
        return new MessageFormat(text, locale == null ? Locale.ROOT : locale).format(args);
    }

    /** The keys of the base file, i.e. the complete key set. */
    public Set<String> keys() {
        return Collections.unmodifiableSet(base.keySet());
    }

    public boolean hasKey(String key) {
        return base.containsKey(key);
    }

    /**
     * Whether texts exist for {@code locale}: a language (or language and
     * region) file, or the base file for {@link #BASE_LANGUAGE}.
     */
    public boolean hasBundle(Locale locale) {
        return isBaseLanguage(locale) || bundleFor(locale) != base;
    }

    /**
     * Keys of the base file that {@code locale}'s language file does not
     * define; every key if there is no such file at all.
     */
    public Set<String> missingKeys(Locale locale) {
        if (!hasBundle(locale)) {
            return new TreeSet<>(base.keySet());
        }
        Set<String> missing = new TreeSet<>(base.keySet());
        missing.removeAll(bundleFor(locale).keySet());
        return missing;
    }

    /** Keys that {@code locale}'s language file defines but the base file does not. */
    public Set<String> extraKeys(Locale locale) {
        Set<String> extra = new TreeSet<>(bundleFor(locale).keySet());
        extra.removeAll(base.keySet());
        return extra;
    }

    // --- loading ---------------------------------------------------------

    private Map<String, String> bundleFor(Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) {
            return base;
        }
        return loaded.computeIfAbsent(locale, this::loadBundle);
    }

    private static boolean isBaseLanguage(Locale locale) {
        return locale != null && locale.getLanguage().equals(BASE_LANGUAGE);
    }

    private Map<String, String> loadBundle(Locale locale) {
        // messages_de_AT before messages_de, like ResourceBundle would.
        String tag = locale.toLanguageTag();
        if (!locale.getCountry().isEmpty()) {
            Map<String, String> regional = read(basePath + "_" + locale.getLanguage()
                    + "_" + locale.getCountry() + ".properties");
            if (regional != null) {
                logCoverage(tag, regional);
                return regional;
            }
        }
        Map<String, String> language = read(basePath + "_" + locale.getLanguage() + ".properties");
        if (language != null) {
            logCoverage(tag, language);
            return language;
        }
        if (!isBaseLanguage(locale)) {
            LOG.warn("No message bundle for {}, using English", tag);
        }
        return base;
    }

    private void logCoverage(String tag, Map<String, String> bundle) {
        long missing = base.keySet().stream().filter(k -> !bundle.containsKey(k)).count();
        if (missing > 0) {
            LOG.warn("Message bundle {}: {} of {} keys missing, falling back to English",
                    tag, missing, base.size());
        } else {
            LOG.debug("Message bundle {} loaded, {} keys", tag, bundle.size());
        }
    }

    /** Reads a properties resource as UTF-8, {@code null} if it does not exist. */
    private static Map<String, String> read(String resource) {
        try (InputStream in = Messages.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<String, String> map = new HashMap<>();
            for (String name : properties.stringPropertyNames()) {
                map.put(name, properties.getProperty(name));
            }
            return map;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read message bundle " + resource, e);
        }
    }
}
