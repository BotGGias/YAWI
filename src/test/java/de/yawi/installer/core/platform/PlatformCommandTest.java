package de.yawi.installer.core.platform;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure string logic of E02-S03, run for all three platforms on any OS. */
class PlatformCommandTest {

    private static final Environment ENV = Environment.of(Map.of(), Map.of());
    private static final Platform WINDOWS = new WindowsPlatform(Architecture.X64, ENV);
    private static final Platform LINUX = new LinuxPlatform(Architecture.X64, ENV);
    private static final Platform MAC = new MacPlatform(Architecture.AARCH64, ENV);

    @Test
    void shellCommandWrapsTheLineUnchanged() {
        String line = "echo \"hi there\" && dir";
        assertEquals(List.of("cmd.exe", "/c", line), WINDOWS.shellCommand(line));
        assertEquals(List.of("/bin/sh", "-c", line), LINUX.shellCommand(line));
        assertEquals(List.of("/bin/sh", "-c", line), MAC.shellCommand(line));
    }

    @Test
    void scriptExtensions() {
        assertEquals(".bat", WINDOWS.scriptExtension());
        assertEquals(".sh", LINUX.scriptExtension());
        assertEquals(".sh", MAC.scriptExtension());
    }

    @Test
    void windowsQuotingUsesDoubleQuotes() {
        assertEquals("\"C:\\Program Files\\YOUR INSTALLER\\yawi.exe\"",
                WINDOWS.quote(Path.of("C:\\Program Files\\YOUR INSTALLER\\yawi.exe")));
        assertEquals("\"C:\\Über Ordner\\x\"", WINDOWS.quote(Path.of("C:\\Über Ordner\\x")));
        assertEquals("\"C:\\it's\\x\"", WINDOWS.quote(Path.of("C:\\it's\\x")));
        assertEquals("\"C:\\say \\\"hi\\\"\\x\"", WINDOWS.quote(Path.of("C:\\say \"hi\"\\x")));
    }

    @Test
    void posixQuotingUsesSingleQuotes() {
        for (Platform posix : List.of(LINUX, MAC)) {
            assertEquals("'/opt/YOUR INSTALLER/su'", posix.quote(Path.of("/opt/YOUR INSTALLER/su")));
            assertEquals("'/home/me/Über Ordner/x'", posix.quote(Path.of("/home/me/Über Ordner/x")));
            assertEquals("'/home/me/say \"hi\"/x'", posix.quote(Path.of("/home/me/say \"hi\"/x")));
            // a single quote ends the quoting, is escaped, and re-opens it
            assertEquals("'/home/me/it'\\''s/x'", posix.quote(Path.of("/home/me/it's/x")));
            assertEquals("'/tmp/$HOME and `id`'", posix.quote(Path.of("/tmp/$HOME and `id`")));
        }
    }
}
