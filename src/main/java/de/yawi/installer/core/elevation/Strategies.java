package de.yawi.installer.core.elevation;

import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.PathLookup;
import de.yawi.installer.core.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The strategies per operating system and their selection. */
final class Strategies {

    private static final Logger LOG = LoggerFactory.getLogger(Strategies.class);

    /** Exit codes the Windows launch script uses for its own outcome (never a child's: those are 0..13). */
    static final int WINDOWS_DECLINED = 121;
    static final int WINDOWS_FAILED = 120;

    static final String PROPERTY = "yawi.elevation";
    static final String NONE = "none";

    private Strategies() {
    }

    static Optional<ElevationStrategy> select(Platform platform, String preference) {
        String wanted = preference == null ? "" : preference.trim().toLowerCase(Locale.ROOT);
        if (NONE.equals(wanted)) {
            LOG.info("Elevation switched off (-D{}=none)", PROPERTY);
            return Optional.empty();
        }
        List<ElevationStrategy> candidates = new ArrayList<>(forOs(platform));
        candidates.add(new Direct());
        if (!wanted.isEmpty()) {
            Optional<ElevationStrategy> named = candidates.stream().filter(s -> s.name().equals(wanted)).findFirst();
            if (named.isEmpty()) {
                LOG.warn("Unknown elevation strategy '{}' (-D{}); choosing automatically", wanted, PROPERTY);
            } else if (!named.get().available(platform)) {
                LOG.warn("Elevation strategy '{}' is not available here", wanted);
                return Optional.empty();
            } else {
                return named;
            }
        }
        for (ElevationStrategy candidate : forOs(platform)) {
            if (candidate.available(platform)) {
                LOG.info("Elevation strategy: {}", candidate.name());
                return Optional.of(candidate);
            }
        }
        LOG.warn("No elevation strategy available on this machine");
        return Optional.empty();
    }

    static List<ElevationStrategy> forOs(Platform platform) {
        return switch (platform.os()) {
            case LINUX -> List.of(new Pkexec(), new SudoAskpass(platform), new SudoTerminal());
            case MACOS -> List.of(new Osascript(), new SudoAskpass(platform), new SudoTerminal());
            case WINDOWS -> List.of(new WindowsRunas());
            default -> List.of();
        };
    }

    /** Standard output goes nowhere (the child reports through files), standard error stays readable. */
    static Process launch(List<String> command, Map<String, String> env, Path cwd, boolean inheritStdin)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(cwd == null ? null : cwd.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.PIPE);
        if (inheritStdin) {
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        } else {
            builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        }
        builder.environment().putAll(env);
        LOG.info("Starting elevated process: {}", SelfCommand.describe(command, env));
        return builder.start();
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null");
    }

    // --- the strategies -----------------------------------------------------

    /** No elevation at all: the child runs as the caller. For tests and developers who are root anyway. */
    static final class Direct implements ElevationStrategy {
        @Override
        public String name() {
            return "direct";
        }

        @Override
        public boolean available(Platform platform) {
            return true;
        }

        @Override
        public boolean elevates() {
            return false;
        }

        @Override
        public Process start(List<String> command, Path workingDirectory) throws IOException {
            return launch(command, Map.of(), workingDirectory, false);
        }

        @Override
        public Outcome classify(int exitCode, String stderrTail) {
            return Outcome.NORMAL;
        }
    }

    /** polkit's {@code pkexec}: graphical prompt through the session's agent. */
    static final class Pkexec implements ElevationStrategy {
        @Override
        public String name() {
            return "pkexec";
        }

        @Override
        public boolean available(Platform platform) {
            boolean display = platform.environment().env("DISPLAY").isPresent()
                    || platform.environment().env("WAYLAND_DISPLAY").isPresent();
            return display && PathLookup.find(platform, "pkexec").isPresent();
        }

        @Override
        public Process start(List<String> command, Path workingDirectory) throws IOException {
            List<String> full = new ArrayList<>();
            full.add("pkexec");
            full.addAll(command);
            return launch(full, Map.of(), workingDirectory, false);
        }

        @Override
        public Outcome classify(int exitCode, String stderrTail) {
            return switch (exitCode) {
                case 126 -> Outcome.DECLINED;
                case 127 -> Outcome.FAILED;
                default -> Outcome.NORMAL;
            };
        }
    }

    /** {@code sudo -A} with an askpass helper: graphical prompt without polkit. */
    static final class SudoAskpass implements ElevationStrategy {
        static final List<String> HELPERS = List.of("ssh-askpass", "ksshaskpass", "lxqt-openssh-askpass",
                "x11-ssh-askpass", "gnome-ssh-askpass");

        private final Platform platform;

        SudoAskpass(Platform platform) {
            this.platform = platform;
        }

        @Override
        public String name() {
            return "sudo-askpass";
        }

        @Override
        public boolean available(Platform platform) {
            return PathLookup.find(platform, "sudo").isPresent() && helper(platform).isPresent();
        }

        static Optional<Path> helper(Platform platform) {
            Optional<Path> configured = platform.environment().env("SUDO_ASKPASS").map(Path::of)
                    .filter(Files::isExecutable);
            if (configured.isPresent()) {
                return configured;
            }
            for (String name : HELPERS) {
                Optional<Path> found = PathLookup.find(platform, name);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }

        @Override
        public Process start(List<String> command, Path workingDirectory) throws IOException {
            Path helper = helper(platform).orElseThrow(() -> new IOException("no askpass helper found"));
            List<String> full = new ArrayList<>(List.of("sudo", "-A", "--"));
            full.addAll(command);
            return launch(full, Map.of("SUDO_ASKPASS", helper.toString()), workingDirectory, false);
        }

        @Override
        public Outcome classify(int exitCode, String stderrTail) {
            return exitCode == 1 ? Outcome.DECLINED : Outcome.NORMAL;
        }
    }

    /** Plain {@code sudo} on the terminal the installer was started from. */
    static final class SudoTerminal implements ElevationStrategy {
        @Override
        public String name() {
            return "sudo";
        }

        @Override
        public boolean available(Platform platform) {
            return System.console() != null && PathLookup.find(platform, "sudo").isPresent();
        }

        @Override
        public Process start(List<String> command, Path workingDirectory) throws IOException {
            List<String> full = new ArrayList<>(List.of("sudo", "--"));
            full.addAll(command);
            return launch(full, Map.of(), workingDirectory, true);
        }

        @Override
        public Outcome classify(int exitCode, String stderrTail) {
            return exitCode == 1 ? Outcome.DECLINED : Outcome.NORMAL;
        }
    }

    /** macOS: {@code osascript} with {@code do shell script … with administrator privileges}. */
    static final class Osascript implements ElevationStrategy {
        @Override
        public String name() {
            return "osascript";
        }

        @Override
        public boolean available(Platform platform) {
            return PathLookup.find(platform, "osascript").isPresent();
        }

        /** The AppleScript expression for {@code command}: sh-quoted, then AppleScript-quoted. */
        static String script(List<String> command) {
            StringBuilder shell = new StringBuilder();
            for (String arg : command) {
                if (!shell.isEmpty()) {
                    shell.append(' ');
                }
                shell.append('\'').append(arg.replace("'", "'\\''")).append('\'');
            }
            String applescript = shell.toString().replace("\\", "\\\\").replace("\"", "\\\"");
            return "do shell script \"" + applescript + "\" with administrator privileges";
        }

        @Override
        public Process start(List<String> command, Path workingDirectory) throws IOException {
            return launch(List.of("osascript", "-e", script(command)), Map.of(), workingDirectory, false);
        }

        @Override
        public Outcome classify(int exitCode, String stderrTail) {
            if (exitCode == 0) {
                return Outcome.NORMAL;
            }
            return stderrTail != null && stderrTail.contains("-128") ? Outcome.DECLINED : Outcome.NORMAL;
        }
    }

    /** Windows: UAC through PowerShell's {@code Start-Process -Verb RunAs}. */
    static final class WindowsRunas implements ElevationStrategy {
        @Override
        public String name() {
            return "runas";
        }

        @Override
        public boolean available(Platform platform) {
            return platform.os() == OperatingSystem.WINDOWS;
        }

        /**
         * The script: waits for the elevated process and exits with its code;
         * UAC's "cancelled by the user" (Win32 error 1223) becomes
         * {@link Strategies#WINDOWS_DECLINED}, anything else that stops the
         * start {@link Strategies#WINDOWS_FAILED}.
         */
        static String script(List<String> command, Path workingDirectory) {
            StringBuilder args = new StringBuilder();
            for (int i = 1; i < command.size(); i++) {
                if (args.length() > 0) {
                    args.append(", ");
                }
                args.append(psQuote(cmdQuote(command.get(i))));
            }
            StringBuilder s = new StringBuilder();
            s.append("$ErrorActionPreference = 'Stop'; try { $p = Start-Process -FilePath ").append(psQuote(command.get(0)));
            if (args.length() > 0) {
                s.append(" -ArgumentList @(").append(args).append(')');
            }
            if (workingDirectory != null) {
                s.append(" -WorkingDirectory ").append(psQuote(workingDirectory.toString()));
            }
            s.append(" -Verb RunAs -WindowStyle Hidden -Wait -PassThru; exit $p.ExitCode }")
                    .append(" catch [System.ComponentModel.Win32Exception] {")
                    .append(" if ($_.Exception.NativeErrorCode -eq 1223) { exit ").append(WINDOWS_DECLINED).append(" };")
                    .append(" exit ").append(WINDOWS_FAILED).append(" }")
                    .append(" catch { exit ").append(WINDOWS_FAILED).append(" }");
            return s.toString();
        }

        /**
         * One argument as the elevated process's command line will carry it
         * (the C runtime's rules: backslashes are only special before a
         * quote or at the end of a quoted argument).
         */
        static String cmdQuote(String arg) {
            if (!arg.isEmpty() && arg.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"')) {
                return arg;
            }
            StringBuilder sb = new StringBuilder("\"");
            int backslashes = 0;
            for (int i = 0; i < arg.length(); i++) {
                char c = arg.charAt(i);
                if (c == '\\') {
                    backslashes++;
                } else if (c == '"') {
                    sb.append("\\".repeat(backslashes * 2 + 1)).append('"');
                    backslashes = 0;
                } else {
                    sb.append("\\".repeat(backslashes)).append(c);
                    backslashes = 0;
                }
            }
            sb.append("\\".repeat(backslashes * 2)).append('"');
            return sb.toString();
        }

        static String psQuote(String text) {
            return "'" + text.replace("'", "''") + "'";
        }

        @Override
        public Process start(List<String> command, Path workingDirectory) throws IOException {
            List<String> full = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-Command", script(command, workingDirectory));
            return launch(full, Map.of(), workingDirectory, false);
        }

        @Override
        public Outcome classify(int exitCode, String stderrTail) {
            return switch (exitCode) {
                case WINDOWS_DECLINED -> Outcome.DECLINED;
                case WINDOWS_FAILED -> Outcome.FAILED;
                default -> Outcome.NORMAL;
            };
        }
    }
}
