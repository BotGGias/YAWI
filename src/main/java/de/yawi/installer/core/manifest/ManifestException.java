package de.yawi.installer.core.manifest;

import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A manifest could not be found, read or was rejected.
 *
 * <p>Carries every problem found, not just the first. The user's text is the
 * generic "manifest invalid" one - the problems are for the packager and go
 * into the detail pane ({@link #detail()}) and the log ({@link #getMessage()}).
 */
public class ManifestException extends InstallerException {

    private final List<ManifestProblem> problems;

    public ManifestException(String summary, List<ManifestProblem> problems) {
        this(summary, problems, null);
    }

    public ManifestException(String summary, List<ManifestProblem> problems, Throwable cause) {
        super(ErrorCode.MANIFEST_INVALID, ErrorCode.MANIFEST_INVALID.messageKey(), null,
                format(summary, problems), format(summary, problems), cause);
        this.problems = List.copyOf(problems);
    }

    public List<ManifestProblem> getProblems() {
        return problems;
    }

    public List<ManifestProblem> getErrors() {
        return problems.stream().filter(ManifestProblem::isError).toList();
    }

    private static String format(String summary, List<ManifestProblem> problems) {
        if (problems.isEmpty()) {
            return summary;
        }
        return summary + "\n" + problems.stream()
                .map(p -> "  " + p)
                .collect(Collectors.joining("\n"));
    }
}
