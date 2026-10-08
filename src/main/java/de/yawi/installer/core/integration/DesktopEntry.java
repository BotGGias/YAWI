package de.yawi.installer.core.integration;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A {@code .desktop} file after the freedesktop.org Desktop Entry
 * Specification (Linux). {@code Exec} is nothing but the quoted
 * path of the target: no arguments, no field codes - a manifest cannot smuggle
 * a command line in here.
 */
public final class DesktopEntry {

    private DesktopEntry() {
    }

    /**
     * @param iconName the icon's theme name ({@link ShortcutLayout#baseName}) or empty for no {@code Icon} line
     */
    public static String render(ShortcutSpec spec, Optional<String> iconName) {
        StringBuilder text = new StringBuilder()
                .append("[Desktop Entry]\n")
                .append("Type=Application\n")
                .append("Version=1.0\n")
                .append("Name=").append(escapeValue(spec.name())).append('\n')
                .append("Exec=").append(quoteExec(spec.target())).append('\n')
                .append("TryExec=").append(escapeValue(spec.target().toString())).append('\n')
                .append("Path=").append(escapeValue(spec.workingDir().toString())).append('\n');
        iconName.ifPresent(icon -> text.append("Icon=").append(escapeValue(icon)).append('\n'));
        return text.append("Terminal=false\n").toString();
    }

    /**
     * A hidden handler {@code .desktop} for a file association: it
     * declares the MIME type and opens the target with the double-clicked file
     * as its argument ({@code %f}). {@code NoDisplay=true} keeps it out of the
     * menu - the visible launcher, if any, is a separate {@code <shortcut>}.
     * The {@code %f} is a fixed field code the installer adds; the target is
     * quoted exactly like {@link #render}'s {@code Exec}, so a manifest still
     * cannot smuggle in a command line.
     */
    public static String renderHandler(AssociationSpec spec) {
        return "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Version=1.0\n"
                + "Name=" + escapeValue(spec.label()) + "\n"
                + "Exec=" + quoteExec(spec.target()) + " %f\n"
                + "TryExec=" + escapeValue(spec.target().toString()) + "\n"
                + "Path=" + escapeValue(spec.workingDir().toString()) + "\n"
                + "MimeType=" + escapeValue(spec.mimeType()) + ";\n"
                + "NoDisplay=true\n"
                + "Terminal=false\n";
    }

    /**
     * The target as one {@code Exec} argument: quoted when it holds a
     * character the spec reserves, with the four characters that keep their
     * meaning inside quotes backslash-escaped.
     */
    public static String quoteExec(Path target) {
        String path = target.toString();
        if (path.chars().noneMatch(c -> " \t\n\"'\\><~|&;$*?#()`%".indexOf(c) >= 0)) {
            return path;
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : path.toCharArray()) {
            if (c == '"' || c == '`' || c == '$' || c == '\\') {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        return quoted.append('"').toString();
    }

    /** A plain value: the spec escapes newlines, tabs and backslashes. */
    static String escapeValue(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r");
    }
}
