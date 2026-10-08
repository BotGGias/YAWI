package de.yawi.installer.core.manifest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A user input declared on a component ({@code <input>}), rendered by E05 and
 * available to steps as {@code ${input.<id>}}.
 *
 * @param defaultValue may be null
 * @param min          lower bound for {@link InputType#INT}, may be null
 * @param max          upper bound for {@link InputType#INT}, may be null
 * @param label        text shown next to the field
 * @param required     an empty value is rejected
 * @param options      the choices of a {@link InputType#CHOICE}; empty for other types
 */
public record ComponentInput(String id, InputType type, String defaultValue, Long min, Long max, String label,
                             boolean required, List<Option> options) {

    public enum InputType {
        STRING, INT, BOOL, CHOICE;

        public static InputType fromManifestName(String name) {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** One {@code <option value="…">Label</option>} of a choice input. */
    public record Option(String value, String label) {
        public Option {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(label, "label");
        }
    }

    public ComponentInput {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(label, "label");
        options = List.copyOf(options);
    }

    /** Optional input without options; kept for callers that predate E05-S05. */
    public ComponentInput(String id, InputType type, String defaultValue, Long min, Long max, String label) {
        this(id, type, defaultValue, min, max, label, false, List.of());
    }

    public Optional<String> defaultValueOrEmpty() {
        return Optional.ofNullable(defaultValue);
    }

    public Optional<Long> minOrEmpty() {
        return Optional.ofNullable(min);
    }

    public Optional<Long> maxOrEmpty() {
        return Optional.ofNullable(max);
    }

    public Optional<Option> option(String value) {
        return options.stream().filter(o -> o.value().equals(value)).findFirst();
    }
}
