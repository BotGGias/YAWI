package de.yawi.installer.core.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Targeted edits of the user's {@code mimeapps.list} (Linux). Only the
 * one {@code <mimeType>=<desktop>} default an association set is removed on
 * uninstall; every other association the user has stays untouched - which is why
 * the file is never treated as an installer-owned file that could be deleted
 * whole.
 */
public final class MimeApps {

    private static final String SECTION = "[Default Applications]";

    private MimeApps() {
    }

    /**
     * Removes the {@code <mimeType>=<desktopFile>} default from the
     * {@code [Default Applications]} section, if present. A missing file or a
     * missing line is a no-op.
     *
     * @return whether a line was removed
     */
    public static boolean removeDefault(Path mimeAppsList, String mimeType, String desktopFile) throws IOException {
        if (!Files.isRegularFile(mimeAppsList)) {
            return false;
        }
        List<String> lines = Files.readAllLines(mimeAppsList, StandardCharsets.UTF_8);
        String wanted = mimeType + "=" + desktopFile;
        String wantedSemicolon = wanted + ";";
        boolean inSection = false;
        boolean removed = false;
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("[")) {
                inSection = trimmed.equals(SECTION);
                out.add(line);
                continue;
            }
            if (inSection && (trimmed.equals(wanted) || trimmed.equals(wantedSemicolon))) {
                removed = true;
                continue;
            }
            out.add(line);
        }
        if (removed) {
            Files.write(mimeAppsList, out, StandardCharsets.UTF_8);
        }
        return removed;
    }
}
