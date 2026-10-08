package de.yawi.installer.core.integration;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Creates a Windows {@code .lnk} the only way the JDK offers without a
 * library: a short PowerShell script over the {@code WScript.Shell} COM
 * object. Every value is a single-quoted PowerShell string,
 * where only the quote itself needs doubling.
 */
public final class WindowsShortcutScript {

    private WindowsShortcutScript() {
    }

    /**
     * @param icon an {@code .ico} file; without one the target's own icon is used
     */
    public static String render(Path lnk, Path target, Path workingDir, Optional<Path> icon) {
        return "$s = (New-Object -ComObject WScript.Shell).CreateShortcut(" + quote(lnk) + "); "
                + "$s.TargetPath = " + quote(target) + "; "
                + "$s.WorkingDirectory = " + quote(workingDir) + "; "
                + "$s.IconLocation = " + quote(icon.orElse(target)) + " + ',0'; "
                + "$s.Save()";
    }

    /** The process that runs {@code script} without profile, prompts or policy in the way. */
    public static List<String> command(String script) {
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-Command", script);
    }

    static String quote(Path path) {
        return "'" + path.toString().replace("'", "''") + "'";
    }
}
