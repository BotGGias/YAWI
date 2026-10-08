package de.yawi.installer.core.manifest;

import java.util.Objects;

/**
 * One finding while reading a manifest: a schema violation with line and
 * column, or a business rule violation naming the affected id.
 *
 * <p>Schema and business problems share this type so the packager sees one
 * list and cannot tell them apart in the presentation.
 *
 * @param line    1-based line, {@code 0} if unknown
 * @param column  1-based column, {@code 0} if unknown
 * @param subject the affected id (e.g. {@code component 'server'}), may be null
 */
public record ManifestProblem(Severity severity, int line, int column, String subject, String message) {

    public enum Severity { WARNING, ERROR }

    public ManifestProblem {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    public static ManifestProblem error(int line, int column, String message) {
        return new ManifestProblem(Severity.ERROR, line, column, null, message);
    }

    public static ManifestProblem error(String subject, String message) {
        return new ManifestProblem(Severity.ERROR, 0, 0, subject, message);
    }

    public static ManifestProblem warning(int line, int column, String message) {
        return new ManifestProblem(Severity.WARNING, line, column, null, message);
    }

    public static ManifestProblem warning(String subject, String message) {
        return new ManifestProblem(Severity.WARNING, 0, 0, subject, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    public boolean hasPosition() {
        return line > 0;
    }

    /** {@code [ERROR] line 12:5 - message} or {@code [ERROR] component 'x' - message}. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[").append(severity).append("] ");
        if (hasPosition()) {
            sb.append("line ").append(line).append(':').append(column).append(" - ");
        } else if (subject != null) {
            sb.append(subject).append(" - ");
        }
        return sb.append(message).toString();
    }
}
