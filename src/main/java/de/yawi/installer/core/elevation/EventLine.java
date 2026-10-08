package de.yawi.installer.core.elevation;

import de.yawi.installer.core.state.InstallationRecord.Entry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The line format of {@code events.log} one event per line,
 * fields separated by tabs, with backslash escaping so any text survives
 * ({@code \t}, {@code \n}, {@code \r}, {@code \\}). Deliberately not JSON -
 * the project has no JSON parser and needs none for a flat line.
 *
 * <p>Event names and fields:
 * {@code ready}; {@code stepStarted id index count}; {@code stepProgress fraction message};
 * {@code stepFinished id outcome}; {@code overall fraction}; {@code output line};
 * {@code rollbackStarted total}; {@code rollbackProgress done total what};
 * {@code rollbackFinished restored deleted noReverse... | failures... | leftBehind...}
 * (each list as one field, entries separated by {@code \n} before escaping);
 * {@code record kind fields...} (a record entry of the elevated segment, {@code PARTIAL} only).
 */
public final class EventLine {

    public static final String READY = "ready";
    public static final String STEP_STARTED = "stepStarted";
    public static final String STEP_PROGRESS = "stepProgress";
    public static final String STEP_FINISHED = "stepFinished";
    public static final String OVERALL = "overall";
    public static final String OUTPUT = "output";
    public static final String ROLLBACK_STARTED = "rollbackStarted";
    public static final String ROLLBACK_PROGRESS = "rollbackProgress";
    public static final String ROLLBACK_FINISHED = "rollbackFinished";
    public static final String UNINSTALL_STARTED = "uninstallStarted";
    public static final String UNINSTALL_PROGRESS = "uninstallProgress";
    public static final String RECORD = "record";

    private EventLine() {
    }

    public static String encode(List<String> fields) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.append('\t');
            }
            String field = fields.get(i) == null ? "" : fields.get(i);
            for (int j = 0; j < field.length(); j++) {
                char c = field.charAt(j);
                switch (c) {
                    case '\\' -> out.append("\\\\");
                    case '\t' -> out.append("\\t");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    default -> out.append(c);
                }
            }
        }
        return out.toString();
    }

    public static String encode(String... fields) {
        return encode(List.of(fields));
    }

    /** @return the fields, or empty for a blank line */
    public static Optional<List<String>> decode(String line) {
        if (line == null || line.isEmpty()) {
            return Optional.empty();
        }
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char next = line.charAt(++i);
                switch (next) {
                    case 't' -> current.append('\t');
                    case 'n' -> current.append('\n');
                    case 'r' -> current.append('\r');
                    default -> current.append(next);
                }
            } else if (c == '\t') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return Optional.of(fields);
    }

    /** A list as one field: entries joined by newlines (escaped by {@link #encode}). */
    public static String list(List<String> items) {
        return String.join("\n", items);
    }

    public static List<String> unlist(String field) {
        return field.isEmpty() ? List.of() : List.of(field.split("\n", -1));
    }

    // --- record entries -----------------------------------------------------

    /** {@code record kind fields...} for one entry of the elevated segment. */
    public static String encodeRecord(Entry entry) {
        return switch (entry) {
            case de.yawi.installer.core.state.InstallationRecord.CreatedFile f -> encode(RECORD, "file", f.path());
            case de.yawi.installer.core.state.InstallationRecord.CreatedDirectory d -> encode(RECORD, "dir", d.path());
            case de.yawi.installer.core.state.InstallationRecord.ReplacedFile r ->
                    encode(RECORD, "replaced", r.path(), r.backup());
            case de.yawi.installer.core.state.InstallationRecord.ModeChanged m -> encode(RECORD, "mode", m.path(), m.mode());
            case de.yawi.installer.core.state.InstallationRecord.StepStarted s -> encode(RECORD, "started", s.stepId());
            case de.yawi.installer.core.state.InstallationRecord.StepFinished s ->
                    encode(RECORD, "finished", s.stepId(), s.outcome(), s.message() == null ? "" : s.message(),
                            s.message() == null ? "0" : "1");
            case de.yawi.installer.core.state.InstallationRecord.RunFinished f -> encode(RECORD, "runFinished", f.at().toString());
        };
    }

    /** @param fields the decoded line starting with {@code record}; empty if it is no valid entry */
    public static Optional<Entry> decodeRecord(List<String> fields) {
        if (fields.size() < 3 || !RECORD.equals(fields.get(0))) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(switch (fields.get(1)) {
                case "file" -> new de.yawi.installer.core.state.InstallationRecord.CreatedFile(fields.get(2));
                case "dir" -> new de.yawi.installer.core.state.InstallationRecord.CreatedDirectory(fields.get(2));
                case "replaced" -> new de.yawi.installer.core.state.InstallationRecord.ReplacedFile(fields.get(2), fields.get(3));
                case "mode" -> new de.yawi.installer.core.state.InstallationRecord.ModeChanged(fields.get(2), fields.get(3));
                case "started" -> new de.yawi.installer.core.state.InstallationRecord.StepStarted(fields.get(2));
                case "finished" -> new de.yawi.installer.core.state.InstallationRecord.StepFinished(fields.get(2), fields.get(3),
                        "1".equals(fields.get(5)) ? fields.get(4) : null);
                case "runFinished" -> new de.yawi.installer.core.state.InstallationRecord.RunFinished(Instant.parse(fields.get(2)));
                default -> null;
            });
        } catch (IndexOutOfBoundsException | java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
