package de.yawi.installer.core.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E13-S01: the .desktop file follows the Desktop Entry Specification. */
class DesktopEntryTest {

    private static ShortcutSpec spec(String name, Path target) {
        return new ShortcutSpec("su", "main", name, target, target.getParent(), Optional.empty(), true, true);
    }

    @Test
    void rendersTheRequiredKeysInOrder() {
        String text = DesktopEntry.render(spec("YOUR INSTALLER", Path.of("/opt/su/bin/su")), Optional.of("su-main"));
        assertEquals(List.of(
                "[Desktop Entry]",
                "Type=Application",
                "Version=1.0",
                "Name=YOUR INSTALLER",
                "Exec=/opt/su/bin/su",
                "TryExec=/opt/su/bin/su",
                "Path=/opt/su/bin",
                "Icon=su-main",
                "Terminal=false"), text.lines().toList());
    }

    @Test
    void withoutIconThereIsNoIconLine() {
        String text = DesktopEntry.render(spec("X", Path.of("/opt/x/x")), Optional.empty());
        assertFalse(text.contains("Icon="), text);
        assertTrue(text.endsWith("Terminal=false\n"));
    }

    @Test
    void execIsQuotedAndEscapedWhenThePathNeedsIt() {
        assertEquals("/opt/su/bin/su", DesktopEntry.quoteExec(Path.of("/opt/su/bin/su")));
        assertEquals("\"/home/me/My Games/su\"", DesktopEntry.quoteExec(Path.of("/home/me/My Games/su")));
        assertEquals("\"/tmp/a\\\"b\\$c\\`d\\\\e\"", DesktopEntry.quoteExec(Path.of("/tmp/a\"b$c`d\\e")));
        assertEquals("\"/tmp/x'y\"", DesktopEntry.quoteExec(Path.of("/tmp/x'y")), "a quote only needs the quoting");
    }

    @Test
    void valuesEscapeControlCharactersAndBackslashes() {
        assertEquals("a\\\\b\\nc\\td", DesktopEntry.escapeValue("a\\b\nc\td"));
    }

    /** E13-S02: the hidden handler .desktop declares the MIME type and opens the file with %f. */
    @Test
    void handlerDeclaresTheMimeTypeAndOpensTheFile() {
        AssociationSpec spec = new AssociationSpec("su", ".sumap", Path.of("/opt/su/bin/su"), Path.of("/opt/su"),
                Optional.of("YOUR INSTALLER Map"));
        String text = DesktopEntry.renderHandler(spec);
        assertTrue(text.contains("Name=YOUR INSTALLER Map\n"), text);
        assertTrue(text.contains("Exec=/opt/su/bin/su %f\n"), text);
        assertTrue(text.contains("MimeType=application/x-vnd.su-sumap;\n"), text);
        assertTrue(text.contains("NoDisplay=true\n"), text);
        assertTrue(text.contains("Path=/opt/su\n"), text);
    }

    @Test
    void handlerQuotesATargetWithSpacesButKeepsTheFieldCode() {
        AssociationSpec spec = new AssociationSpec("su", ".sumap", Path.of("/opt/my su/bin/su"), Path.of("/opt/my su"),
                Optional.empty());
        assertTrue(DesktopEntry.renderHandler(spec).contains("Exec=\"/opt/my su/bin/su\" %f\n"),
                DesktopEntry.renderHandler(spec));
    }
}
