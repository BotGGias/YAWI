package de.yawi.installer.core.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E13-S03: the pure PATH-block edits of a shell profile. */
class ShellProfileTest {

    private static final List<String> DIRS = List.of("/opt/su/bin", "/opt/su/tools");

    @Test
    void appendAddsAMarkedBlockWithTheExportLine() {
        String out = ShellProfile.appendBlock("", "your-product", DIRS);
        assertTrue(out.contains(ShellProfile.beginMarker("your-product")), out);
        assertTrue(out.contains(ShellProfile.endMarker("your-product")), out);
        assertTrue(out.contains("export PATH=\"/opt/su/bin:/opt/su/tools:$PATH\"\n"), out);
    }

    @Test
    void appendNeverDuplicatesTheBlock() {
        String once = ShellProfile.appendBlock("", "su", DIRS);
        String twice = ShellProfile.appendBlock(once, "su", DIRS);
        assertEquals(once, twice, "the block is added at most once");
        assertTrue(ShellProfile.containsBlock(once, "su"));
    }

    @Test
    void appendKeepsForeignContentAndSeparatesTheBlock() {
        String existing = "export EDITOR=vim"; // no trailing newline
        String out = ShellProfile.appendBlock(existing, "su", DIRS);
        assertTrue(out.startsWith("export EDITOR=vim\n"), out);
        assertTrue(out.contains(ShellProfile.beginMarker("su")), out);
    }

    @Test
    void removeCutsExactlyTheBlockAndKeepsForeignLines() {
        String existing = "# mine\nexport EDITOR=vim\n";
        String withBlock = ShellProfile.appendBlock(existing, "su", DIRS);
        ShellProfile.Removal removal = ShellProfile.removeBlock(withBlock, "su");
        assertTrue(removal.removed());
        assertFalse(ShellProfile.containsBlock(removal.content(), "su"));
        assertTrue(removal.content().contains("export EDITOR=vim"), removal.content());
        assertFalse(removal.content().contains("export PATH="), removal.content());
    }

    @Test
    void removeOnAFileWithoutTheBlockChangesNothing() {
        String existing = "export EDITOR=vim\n";
        ShellProfile.Removal removal = ShellProfile.removeBlock(existing, "su");
        assertFalse(removal.removed());
        assertEquals(existing, removal.content());
    }

    @Test
    void removeLeavesAnotherProductsBlockAlone() {
        String content = ShellProfile.appendBlock(ShellProfile.appendBlock("", "one", DIRS), "two", DIRS);
        ShellProfile.Removal removal = ShellProfile.removeBlock(content, "one");
        assertTrue(removal.removed());
        assertFalse(ShellProfile.containsBlock(removal.content(), "one"));
        assertTrue(ShellProfile.containsBlock(removal.content(), "two"), removal.content());
    }
}
