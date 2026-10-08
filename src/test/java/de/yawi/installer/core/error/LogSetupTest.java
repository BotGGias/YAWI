package de.yawi.installer.core.error;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E17-S03-T01: the log can be moved at run time; the old location keeps a pointer. */
class LogSetupTest {

    @TempDir
    Path tmp;

    @Test
    void redirectMovesTheLogAndLeavesAPointer() throws Exception {
        Path before = LogSetup.currentLogFile();
        Path target = tmp.resolve("logs");
        try {
            Path moved = LogSetup.redirect(target);
            assertEquals(target.resolve(LogSetup.FILE_NAME), moved);
            assertEquals(moved, LogSetup.currentLogFile());

            LoggerFactory.getLogger(LogSetupTest.class).info("marker line after redirect");

            String text = Files.readString(moved);
            assertTrue(text.contains("marker line after redirect"), text);
            assertTrue(text.contains("Log continues here"), text);
            assertTrue(Files.readString(before).contains("Log moves to " + moved), "pointer in the old file");
        } finally {
            // Other tests expect the default location.
            LogSetup.redirect(before.getParent());
        }
        assertEquals(before, LogSetup.currentLogFile());
        LoggerFactory.getLogger(LogSetupTest.class).info("marker line after moving back");
        assertTrue(Files.readString(before).contains("marker line after moving back"));
    }

    @Test
    void redirectToTheCurrentDirectoryIsANoOp() {
        Path before = LogSetup.currentLogFile();
        assertEquals(before, LogSetup.redirect(before.getParent()));
    }
}
