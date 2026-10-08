package de.yawi.installer.core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E13-S02: the targeted removal of one default from mimeapps.list. */
class MimeAppsTest {

    @TempDir
    Path tmp;

    @Test
    void removesOnlyTheOneDefaultAndKeepsTheRest() throws IOException {
        Path list = tmp.resolve("mimeapps.list");
        Files.writeString(list, "[Default Applications]\n"
                + "application/x-vnd.your-product-sumap=your-product-sumap.desktop;\n"
                + "text/plain=gedit.desktop\n"
                + "[Added Associations]\napplication/pdf=evince.desktop;\n", StandardCharsets.UTF_8);

        boolean removed = MimeApps.removeDefault(list, "application/x-vnd.your-product-sumap",
                "your-product-sumap.desktop");

        assertTrue(removed);
        List<String> lines = Files.readAllLines(list);
        assertFalse(lines.stream().anyMatch(l -> l.contains("your-product-sumap.desktop")), lines.toString());
        assertTrue(lines.contains("text/plain=gedit.desktop"), "other default kept");
        assertTrue(lines.contains("application/pdf=evince.desktop;"), "other section kept");
    }

    @Test
    void aMissingFileOrLineIsANoOp() throws IOException {
        assertFalse(MimeApps.removeDefault(tmp.resolve("none.list"), "a/b", "c.desktop"));
        Path list = tmp.resolve("mimeapps.list");
        Files.writeString(list, "[Default Applications]\ntext/plain=gedit.desktop\n");
        assertFalse(MimeApps.removeDefault(list, "a/b", "c.desktop"));
        assertEquals(List.of("[Default Applications]", "text/plain=gedit.desktop"), Files.readAllLines(list));
    }
}
