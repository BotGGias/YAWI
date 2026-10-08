package de.yawi.installer.core.elevation;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * How to start this installer again as another process: the
 * jpackage launcher when running from an app image, otherwise the same JVM
 * with the same module path or class path and the main class. Nothing else
 * of the command line is repeated; the child gets everything it needs from
 * the plan file.
 */
public final class SelfCommand {

    public static final String MAIN_CLASS = "de.yawi.installer.Main";

    private SelfCommand() {
    }

    public static List<String> resolve() {
        return resolve(System.getProperties(), ProcessHandle.current().info().command());
    }

    /**
     * @param properties     the system properties ({@code jpackage.app-path}, {@code jdk.module.*}, {@code java.class.path}, {@code java.home})
     * @param currentCommand the running executable, if the OS tells
     */
    static List<String> resolve(Properties properties, Optional<String> currentCommand) {
        String launcher = properties.getProperty("jpackage.app-path");
        if (launcher != null && !launcher.isBlank()) {
            return List.of(Path.of(launcher).toAbsolutePath().toString());
        }
        List<String> command = new ArrayList<>();
        command.add(java(properties, currentCommand));
        command.add("-Djava.awt.headless=true");
        String mainModule = properties.getProperty("jdk.module.main");
        if (mainModule != null && !mainModule.isBlank()) {
            String modulePath = properties.getProperty("jdk.module.path");
            if (modulePath != null && !modulePath.isBlank()) {
                command.add("--module-path");
                command.add(absolutise(modulePath));
            }
            String addModules = properties.getProperty("jdk.module.addmods");
            if (addModules != null && !addModules.isBlank()) {
                command.add("--add-modules");
                command.add(addModules);
            }
            String classPath = properties.getProperty("java.class.path");
            if (classPath != null && !classPath.isBlank()) {
                command.add("-cp");
                command.add(absolutise(classPath));
            }
            command.add("-m");
            command.add(mainModule + "/" + MAIN_CLASS);
        } else {
            command.add("-cp");
            command.add(absolutise(properties.getProperty("java.class.path", "")));
            command.add(MAIN_CLASS);
        }
        return List.copyOf(command);
    }

    private static String java(Properties properties, Optional<String> currentCommand) {
        Optional<Path> running = currentCommand.map(Path::of).filter(Files::isExecutable)
                .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith("java"));
        if (running.isPresent()) {
            return running.get().toAbsolutePath().toString();
        }
        String home = properties.getProperty("java.home", "");
        String exe = File.separatorChar == '\\' ? "java.exe" : "java";
        return Path.of(home, "bin", exe).toAbsolutePath().toString();
    }

    /** The elevated process may start elsewhere (pkexec, sudo change the working directory). */
    private static String absolutise(String pathList) {
        List<String> parts = new ArrayList<>();
        for (String part : pathList.split(File.pathSeparator)) {
            if (!part.isBlank()) {
                parts.add(Path.of(part).toAbsolutePath().normalize().toString());
            }
        }
        return String.join(File.pathSeparator, parts);
    }

    /** For a report: the command with the environment variables a strategy adds. */
    static String describe(List<String> command, Map<String, String> env) {
        StringBuilder sb = new StringBuilder();
        env.forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
        sb.append(String.join(" ", command));
        return sb.toString();
    }
}
