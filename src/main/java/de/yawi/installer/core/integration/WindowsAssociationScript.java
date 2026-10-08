package de.yawi.installer.core.integration;

import java.util.List;

/**
 * The {@code reg} command lines that register a file association on Windows
 * Keys go under {@code Software\Classes} - {@code HKCU} for a
 * per-user installation (no admin), {@code HKLM} for a system-wide one. The
 * {@code open} command is only the quoted target plus {@code "%1"}: a manifest
 * cannot smuggle a command line in here (same posture as the shortcut script).
 */
public final class WindowsAssociationScript {

    private WindowsAssociationScript() {
    }

    /** {@code HKCU\Software\Classes} or {@code HKLM\Software\Classes}. */
    public static String classesRoot(boolean systemWide) {
        return (systemWide ? "HKLM" : "HKCU") + "\\Software\\Classes";
    }

    /** The three {@code reg add} argument lists that create the association. */
    public static List<List<String>> addCommands(AssociationSpec spec, boolean systemWide) {
        String root = classesRoot(systemWide);
        String progId = spec.progId();
        String open = "\"" + spec.target() + "\" \"%1\"";
        return List.of(
                List.of("reg", "add", root + "\\" + spec.extension(), "/ve", "/d", progId, "/f"),
                List.of("reg", "add", root + "\\" + progId, "/ve", "/d", spec.label(), "/f"),
                List.of("reg", "add", root + "\\" + progId + "\\shell\\open\\command", "/ve", "/d", open, "/f"));
    }

    /** The {@code reg delete} argument lists that remove it again (uninstall). */
    public static List<List<String>> deleteCommands(String productId, String extension, boolean systemWide) {
        String root = classesRoot(systemWide);
        String progId = AssociationSpec.progId(productId, extension);
        return List.of(
                List.of("reg", "delete", root + "\\" + progId, "/f"),
                List.of("reg", "delete", root + "\\" + extension, "/ve", "/f"));
    }
}
