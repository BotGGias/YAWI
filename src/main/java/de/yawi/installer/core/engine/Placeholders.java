package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.InputValidator;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestProblem;
import de.yawi.installer.core.platform.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that resolves {@code ${...}} placeholders in manifest text:
 * {@code ${destination}}, {@code ${input.<id>}}, {@code ${product.version}},
 * {@code ${tempDir}} (the installer's own scratch directory, see
 * {@link ExecutionContext#workDir}), {@code ${os}}, {@code ${arch}}.
 *
 * <p>Inputs of <em>selected</em> components carry the user's value; inputs of
 * deselected components resolve to their manifest default, never to a value
 * the user typed before deselecting. A template of the core
 * component may therefore reference the server's port and still get a
 * sensible number. Unknown placeholders are reported as manifest problems
 * when the plan is built ({@link #validate}), never silently left in place.
 * Resolution is textual - a placeholder inside a process argument stays one
 * argument, nothing is ever handed to a shell here.
 */
public final class Placeholders {

    static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_.-]*)}");

    private final Map<String, String> values;

    private Placeholders(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /**
     * @param inputs the input values from the model, keyed by input id; used
     *               for {@code selectedComponents} only, the rest gets defaults
     */
    public static Placeholders of(InstallManifest manifest, Platform platform, Path destination,
                                  Map<String, String> inputs, Collection<String> selectedComponents) {
        Objects.requireNonNull(destination, "destination");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("destination", destination.toString());
        values.put("product.version", manifest.product().version());
        values.put("tempDir", ExecutionContext.workDir(platform).toString());
        values.put("os", platform.os().manifestName().orElse(platform.os().name().toLowerCase(java.util.Locale.ROOT)));
        values.put("arch", platform.arch().manifestName().orElse(platform.arch().name().toLowerCase(java.util.Locale.ROOT)));
        for (Component component : manifest.components()) {
            boolean selected = selectedComponents.contains(component.id());
            component.inputs().forEach(input -> values.put("input." + input.id(),
                    selected ? inputs.getOrDefault(input.id(), InputValidator.defaultValue(input))
                            : InputValidator.defaultValue(input)));
        }
        return new Placeholders(values);
    }

    /** For tests and the dry run: fixed values, nothing derived. */
    public static Placeholders ofValues(Map<String, String> values) {
        return new Placeholders(values);
    }

    /** The known names, e.g. {@code destination}, {@code input.serverPort}. */
    public Map<String, String> values() {
        return values;
    }

    /**
     * The text with every known placeholder replaced. Unknown ones are left
     * untouched, so a caller that skipped {@link #validate} still sees them.
     */
    public String resolve(String text) {
        if (text == null || text.indexOf('$') < 0) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = values.getOrDefault(matcher.group(1), matcher.group());
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Every placeholder name used in {@code text}, in order of appearance. */
    public static List<String> namesIn(String text) {
        List<String> names = new ArrayList<>();
        if (text == null) {
            return names;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** A problem for every placeholder in {@code text} this instance cannot resolve. */
    public List<ManifestProblem> validate(String subject, String text) {
        List<ManifestProblem> problems = new ArrayList<>();
        for (String name : namesIn(text)) {
            if (!values.containsKey(name)) {
                String hint = name.startsWith("input.") ? "no such input" : "unknown placeholder";
                problems.add(ManifestProblem.error(subject, "${" + name + "}: " + hint));
            }
        }
        return problems;
    }
}
