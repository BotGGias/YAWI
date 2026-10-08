package de.yawi.installer.core.error;

import de.yawi.installer.core.i18n.Messages;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Base of every failure the installer reports.
 *
 * <p>Two texts live here: {@link #getMessage()} is technical, English, with
 * paths and causes, and goes to the log. The user's text is a bundle key with
 * arguments ({@link #userMessage}), resolved only when shown, so the same
 * exception renders in the wizard's language and in the silent mode's
 * {@code --lang}. Every code also has a hint what to do ({@link #hint}) and
 * an optional multi-line {@link #detail()} for a collapsible detail pane
 * , e.g. the list of manifest problems.
 *
 * <p>Unchecked on purpose: the callers that can do something about a failure
 * are the top-level handlers (wizard, silent mode), not every method in
 * between.
 */
public class InstallerException extends RuntimeException {

    private final ErrorCode code;
    private final String messageKey;
    private final Object[] userArgs;
    private final String detail;

    /**
     * @param code             the cause; supplies the exit code and the hint
     * @param messageKey       bundle key of the user's text, usually {@code code.messageKey()}
     * @param userArgs         {@code MessageFormat} arguments of the user's text; numbers
     *                         and sizes go in pre-formatted as text
     * @param technicalMessage what the log shows, may not be null
     * @param detail           multi-line technical detail for the detail pane, or null
     * @param cause            the wrapped exception, or null
     */
    protected InstallerException(ErrorCode code, String messageKey, Object[] userArgs,
                                 String technicalMessage, String detail, Throwable cause) {
        super(Objects.requireNonNull(technicalMessage, "technicalMessage"), cause);
        this.code = Objects.requireNonNull(code, "code");
        this.messageKey = Objects.requireNonNull(messageKey, "messageKey");
        this.userArgs = userArgs == null ? new Object[0] : userArgs.clone();
        this.detail = detail == null || detail.isBlank() ? null : detail;
    }

    /** A failure of {@code code} whose user text is the code's own, with the given arguments. */
    public InstallerException(ErrorCode code, String technicalMessage, Throwable cause, Object... userArgs) {
        this(code, code.messageKey(), userArgs, technicalMessage, null, cause);
    }

    public InstallerException(ErrorCode code, String technicalMessage, Object... userArgs) {
        this(code, technicalMessage, (Throwable) null, userArgs);
    }

    public ErrorCode code() {
        return code;
    }

    /** The silent mode's exit status for this failure. */
    public int exitCode() {
        return code.exitCode();
    }

    public String messageKey() {
        return messageKey;
    }

    public Object[] userArgs() {
        return userArgs.clone();
    }

    /** What happened and why, in the user's language. */
    public String userMessage(Messages messages, Locale locale) {
        return messages.get(locale, messageKey, userArgs);
    }

    /** What the user can do about it, in the user's language. */
    public String hint(Messages messages, Locale locale) {
        return messages.get(locale, code.hintKey());
    }

    /** Multi-line technical detail for a collapsible pane; never a stack trace. */
    public Optional<String> detail() {
        return Optional.ofNullable(detail);
    }

    /**
     * The top-level handlers' last resort: an {@code InstallerException} is
     * returned as it is, anything else becomes {@link ErrorCode#GENERAL}
     * with the original as cause. The user's text then carries the
     * original's message, which is all that is known.
     */
    public static InstallerException wrap(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        if (throwable instanceof InstallerException installer) {
            return installer;
        }
        String message = throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
        return new InstallerException(ErrorCode.GENERAL,
                throwable.getClass().getName() + ": " + message, throwable, message);
    }
}
