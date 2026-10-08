package de.yawi.installer.core.manifest;

import java.util.Optional;

/**
 * Checks a value against its {@code <input>} declaration. Headless: the UI
 * marks the field and translates the problem's key, the CLI reports it.
 */
public final class InputValidator {

    /** A violation as a bundle key plus {@code MessageFormat} arguments. */
    public record Problem(String key, Object... args) {
    }

    public static final String REQUIRED = "input.error.required";
    public static final String NOT_A_NUMBER = "input.error.notANumber";
    public static final String TOO_SMALL = "input.error.tooSmall";
    public static final String TOO_LARGE = "input.error.tooLarge";
    public static final String NOT_AN_OPTION = "input.error.notAnOption";
    public static final String NOT_A_BOOLEAN = "input.error.notABoolean";

    private InputValidator() {
    }

    /** Empty means valid. A {@code null} value counts as empty. */
    public static Optional<Problem> validate(ComponentInput input, String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return input.required() || input.type() != ComponentInput.InputType.STRING
                    ? Optional.of(new Problem(REQUIRED)) : Optional.empty();
        }
        return switch (input.type()) {
            case STRING -> Optional.empty();
            case INT -> validateInt(input, text);
            case BOOL -> text.equals("true") || text.equals("false")
                    ? Optional.empty() : Optional.of(new Problem(NOT_A_BOOLEAN));
            case CHOICE -> input.option(text).isPresent()
                    ? Optional.empty() : Optional.of(new Problem(NOT_AN_OPTION));
        };
    }

    private static Optional<Problem> validateInt(ComponentInput input, String text) {
        long number;
        try {
            number = Long.parseLong(text);
        } catch (NumberFormatException e) {
            return Optional.of(new Problem(NOT_A_NUMBER));
        }
        // Bounds go in as text: a port limit of 65535 must not become "65,535".
        if (input.min() != null && number < input.min()) {
            return Optional.of(new Problem(TOO_SMALL, Long.toString(input.min())));
        }
        if (input.max() != null && number > input.max()) {
            return Optional.of(new Problem(TOO_LARGE, Long.toString(input.max())));
        }
        return Optional.empty();
    }

    /**
     * The value a field starts with: the declared default, else something
     * sensible for the type so that {@code ${input.<id>}} never resolves to
     * nothing for bool and choice.
     */
    public static String defaultValue(ComponentInput input) {
        if (input.defaultValue() != null) {
            return input.defaultValue();
        }
        return switch (input.type()) {
            case BOOL -> "false";
            case CHOICE -> input.options().isEmpty() ? "" : input.options().get(0).value();
            case STRING, INT -> "";
        };
    }
}
