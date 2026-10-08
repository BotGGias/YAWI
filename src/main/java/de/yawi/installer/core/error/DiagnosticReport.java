package de.yawi.installer.core.error;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestOrigin;
import de.yawi.installer.core.manifest.ResourceRef;
import de.yawi.installer.core.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text a user sends to the vendor when something went wrong:
 * failure, system, selection, manifest, installation record and the tail of
 * the log - with the user's home path and name masked and credentials in
 * URLs blanked. Plain text, one section per source, so it can be read before
 * it is sent.
 */
public final class DiagnosticReport {

    private static final Logger LOG = LoggerFactory.getLogger(DiagnosticReport.class);

    /** How much of the log goes into the report. */
    public static final int MAX_LOG_LINES = 2000;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern CREDENTIAL_PARAM =
            Pattern.compile("(?i)([?&](?:[a-z_-]*(?:token|key|password|passwd|secret|sig|signature|auth)[a-z_-]*)=)[^&#\\s\"']*");

    /**
     * What the report is built from; everything but platform and log file may be
     * absent (a startup failure has no manifest, a success no failure).
     *
     * @param inputs already without secrets ({@code InstallationRecord.withoutSecrets})
     */
    public record Context(Optional<InstallManifest> manifest, Platform platform, Optional<Path> destination,
                          Set<String> components, Map<String, String> inputs, Optional<Path> recordFile,
                          Optional<InstallerException> failure, Path logFile) {
        public Context {
            Objects.requireNonNull(platform, "platform");
            Objects.requireNonNull(logFile, "logFile");
            components = Set.copyOf(components);
            inputs = Map.copyOf(inputs);
        }
    }

    private DiagnosticReport() {
    }

    /** The file name proposed for saving: {@code yawi-installer-report-<timestamp>.txt}. */
    public static String defaultFileName() {
        return "yawi-installer-report-" + FILE_STAMP.format(Instant.now().atZone(ZoneId.systemDefault())) + ".txt";
    }

    /** Builds and masks the complete report. Never throws for missing pieces; they are noted instead. */
    public static String build(Context context) {
        StringBuilder out = new StringBuilder();
        section(out, "YAWI diagnostic report");
        out.append("Created: ").append(Instant.now()).append('\n');
        context.manifest().ifPresent(m -> out.append("Product: ").append(m.product().id())
                .append(' ').append(m.product().version()).append(" (").append(m.product().name()).append(")\n"));

        section(out, "Failure");
        context.failure().ifPresentOrElse(f -> {
            out.append("Code: ").append(f.code()).append(" (exit ").append(f.exitCode()).append(")\n");
            out.append("Message: ").append(f.getMessage()).append('\n');
            for (Throwable cause = f.getCause(); cause != null; cause = cause.getCause()) {
                out.append("Caused by: ").append(cause.getClass().getName()).append(": ").append(cause.getMessage()).append('\n');
            }
            f.detail().ifPresent(d -> out.append("Detail:\n").append(d).append('\n'));
        }, () -> out.append("(none)\n"));

        section(out, "System");
        out.append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n");
        out.append("Platform: ").append(context.platform()).append(", elevated=").append(context.platform().isElevated()).append('\n');
        out.append("Java: ").append(System.getProperty("java.version")).append(' ').append(System.getProperty("java.vendor")).append('\n');
        out.append("Locale: ").append(java.util.Locale.getDefault()).append('\n');
        context.destination().ifPresent(dest -> {
            out.append("Destination: ").append(dest).append('\n');
            try {
                out.append("Free space at destination: ").append(context.platform().usableSpace(dest)).append(" bytes\n");
            } catch (IOException | RuntimeException e) {
                out.append("Free space at destination: unknown (").append(e).append(")\n");
            }
        });

        section(out, "Selection");
        out.append("Components: ").append(context.components().isEmpty() ? "(none)"
                : String.join(", ", context.components().stream().sorted().toList())).append('\n');
        if (context.inputs().isEmpty()) {
            out.append("Inputs: (none)\n");
        } else {
            context.inputs().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> out.append("Input ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n'));
        }

        section(out, "Manifest");
        context.manifest().ifPresentOrElse(m -> {
            ManifestOrigin origin = m.origin();
            out.append("Origin: ").append(origin.kind()).append(" (").append(origin.location()).append(")\n");
            manifestText(origin).ifPresentOrElse(text -> out.append(text).append('\n'),
                    () -> out.append("(text not available)\n"));
        }, () -> out.append("(not loaded)\n"));

        section(out, "Installation record");
        context.recordFile().filter(Files::isRegularFile).ifPresentOrElse(file -> {
            try {
                out.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            } catch (IOException e) {
                out.append("(unreadable: ").append(e).append(")\n");
            }
        }, () -> out.append("(none)\n"));

        section(out, "Log (" + context.logFile() + ")");
        out.append(logTail(context.logFile()));
        // The elevated process logs on its own; its copy lies next to the main log after the run.
        Path elevated = context.logFile().resolveSibling(ELEVATED_LOG_NAME);
        if (Files.isRegularFile(elevated)) {
            section(out, "Elevated process log (" + elevated + ")");
            out.append(logTail(elevated));
        }
        return mask(out.toString());
    }

    private static void section(StringBuilder out, String title) {
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append("=== ").append(title).append(" ===\n");
    }

    private static Optional<String> manifestText(ManifestOrigin origin) {
        try {
            return switch (origin.kind()) {
                case CLASSPATH -> Optional.of(ResourceRef.readText(ResourceRef.CLASSPATH_PREFIX + origin.location()));
                case EXPLICIT_FILE, APP_DIR -> Optional.of(Files.readString(Path.of(origin.location()), StandardCharsets.UTF_8));
                case EXPLICIT_URL -> Optional.empty();
            };
        } catch (IOException | RuntimeException e) {
            LOG.debug("Manifest text for the report not readable: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Where {@code core.elevation.ElevatedRun} keeps the child's log (a name, not a dependency). */
    static final String ELEVATED_LOG_NAME = "installer-elevated.log";

    /** The last {@link #MAX_LOG_LINES} lines, with a note when cut. */
    static String logTail(Path logFile) {
        if (!Files.isRegularFile(logFile)) {
            return "(no log file at " + logFile + ")\n";
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(unreadable: " + e + ")\n";
        }
        StringBuilder out = new StringBuilder();
        if (lines.size() > MAX_LOG_LINES) {
            out.append("[... ").append(lines.size() - MAX_LOG_LINES).append(" earlier lines omitted ...]\n");
            lines = lines.subList(lines.size() - MAX_LOG_LINES, lines.size());
        }
        lines.forEach(l -> out.append(l).append('\n'));
        return out.toString();
    }

    /**
     * Replaces the user's home directory with {@code ~} and the user name with
     * {@code [user]} wherever they appear, and blanks credential-looking URL
     * query parameters. Applied to the whole report so nothing is forgotten.
     */
    public static String mask(String text) {
        String result = CREDENTIAL_PARAM.matcher(text).replaceAll("$1[masked]");
        List<String> homes = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && !home.equals("/")) {
            homes.add(home);
            String other = home.contains("\\") ? home.replace('\\', '/') : home.replace('/', '\\');
            homes.add(other);
            String uri = Path.of(home).toUri().toString();
            homes.add(uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri);
        }
        homes.sort((a, b) -> b.length() - a.length());
        for (String h : homes) {
            result = result.replace(h, "~");
        }
        String user = System.getProperty("user.name");
        if (user != null && user.length() > 1) {
            result = Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(user) + "(?![A-Za-z0-9])")
                    .matcher(result).replaceAll(Matcher.quoteReplacement("[user]"));
        }
        return result;
    }

    /** Writes the report as UTF-8 and returns the file. */
    public static Path save(String report, Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, report, StandardCharsets.UTF_8);
        LOG.info("Diagnostic report saved to {}", file);
        return file;
    }
}
