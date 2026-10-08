package de.yawi.installer.core.error;

import java.nio.file.Path;
import java.util.List;

/**
 * An answer file ({@code --config}) that cannot be used: unreadable,
 * malformed, against the schema, or not matching the manifest. Exit code 2
 * like any other bad argument, but the user's text names the file and the
 * first problem, and {@link #detail()} lists every problem - a missing
 * mandatory field is never a silent guess.
 */
public class AnswerFileException extends InstallerException {

    private final Path file;
    private final List<String> problems;

    /** @param problems one line each, the first is the headline; must not be empty */
    public AnswerFileException(Path file, List<String> problems, Throwable cause) {
        super(ErrorCode.INVALID_ARGUMENTS, "error.answers", new Object[] {file, problems.get(0)},
                "answer file " + file + ": " + String.join("; ", problems),
                problems.size() > 1 ? String.join("\n", problems) : null, cause);
        this.file = file;
        this.problems = List.copyOf(problems);
    }

    public AnswerFileException(Path file, String problem) {
        this(file, List.of(problem), null);
    }

    public Path file() {
        return file;
    }

    public List<String> problems() {
        return problems;
    }
}
