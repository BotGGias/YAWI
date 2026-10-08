package de.yawi.installer.core.elevation;

import de.yawi.installer.core.platform.Environment;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.PlatformFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StrategiesTest {

    @TempDir
    Path tmp;

    private static Platform of(String os, Map<String, String> env) {
        return PlatformFactory.detect(os, "amd64", Environment.of(env, Map.of("user.home", "/home/me",
                "java.io.tmpdir", "/tmp")));
    }

    private Path fakeTool(String name) throws IOException {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path tool = Files.writeString(bin.resolve(name), "#!/bin/sh\nexit 0\n");
        if (HandoverDir.posix()) {
            Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return tool;
    }

    @Test
    void linuxPrefersPkexecThenAskpassThenNothingWithoutATerminal() throws IOException {
        assumeTrue(HandoverDir.posix());
        String path = tmp.resolve("bin").toString();
        Platform bare = of("Linux", Map.of("PATH", path, "DISPLAY", ":0"));
        assertEquals(Optional.empty(), Strategies.select(bare, null), "no tools at all");

        fakeTool("sudo");
        fakeTool("ssh-askpass");
        assertEquals("sudo-askpass", Strategies.select(bare, null).orElseThrow().name());

        fakeTool("pkexec");
        assertEquals("pkexec", Strategies.select(bare, null).orElseThrow().name());
        Platform noDisplay = of("Linux", Map.of("PATH", path));
        assertEquals("sudo-askpass", Strategies.select(noDisplay, null).orElseThrow().name(),
                "pkexec needs a graphical session");

        assertEquals("direct", Strategies.select(bare, "direct").orElseThrow().name());
        assertEquals(Optional.empty(), Strategies.select(bare, "none"));
        assertEquals("pkexec", Strategies.select(bare, "no-such").orElseThrow().name(), "unknown name: automatic");
    }

    @Test
    void askpassHelperComesFromTheEnvironmentFirst() throws IOException {
        assumeTrue(HandoverDir.posix());
        Path own = fakeTool("my-askpass");
        fakeTool("ssh-askpass");
        Platform p = of("Linux", Map.of("PATH", tmp.resolve("bin").toString(), "SUDO_ASKPASS", own.toString()));
        assertEquals(own, Strategies.SudoAskpass.helper(p).orElseThrow());
        Platform q = of("Linux", Map.of("PATH", tmp.resolve("bin").toString()));
        assertEquals(tmp.resolve("bin").resolve("ssh-askpass"), Strategies.SudoAskpass.helper(q).orElseThrow());
    }

    @Test
    void exitCodesAreClassifiedPerStrategy() {
        Strategies.Pkexec pkexec = new Strategies.Pkexec();
        assertEquals(ElevationStrategy.Outcome.DECLINED, pkexec.classify(126, ""));
        assertEquals(ElevationStrategy.Outcome.FAILED, pkexec.classify(127, ""));
        assertEquals(ElevationStrategy.Outcome.NORMAL, pkexec.classify(9, ""));
        Strategies.Osascript osa = new Strategies.Osascript();
        assertEquals(ElevationStrategy.Outcome.DECLINED, osa.classify(1, "execution error: User canceled. (-128)"));
        assertEquals(ElevationStrategy.Outcome.NORMAL, osa.classify(1, "something else"));
        Strategies.WindowsRunas runas = new Strategies.WindowsRunas();
        assertEquals(ElevationStrategy.Outcome.DECLINED, runas.classify(Strategies.WINDOWS_DECLINED, ""));
        assertEquals(ElevationStrategy.Outcome.FAILED, runas.classify(Strategies.WINDOWS_FAILED, ""));
        assertEquals(ElevationStrategy.Outcome.NORMAL, runas.classify(0, ""));
        assertFalse(new Strategies.Direct().elevates());
    }

    @Test
    void scriptsQuoteEveryArgument() {
        List<String> command = List.of("C:\\Program Files\\yawi\\yawi-installer.exe", "--elevated-run=C:\\Users\\me\\t mp\\e");
        String ps = Strategies.WindowsRunas.script(command, Path.of("C:\\Users\\me"));
        assertTrue(ps.contains("-FilePath 'C:\\Program Files\\yawi\\yawi-installer.exe'"), ps);
        assertTrue(ps.contains("-ArgumentList @('\"--elevated-run=C:\\Users\\me\\t mp\\e\"')"), ps);
        assertEquals("\"a b\\\\\"", Strategies.WindowsRunas.cmdQuote("a b\\"), "trailing backslash doubled");
        assertEquals("\"say \\\"hi\\\"\"", Strategies.WindowsRunas.cmdQuote("say \"hi\""));
        assertTrue(ps.contains("-Verb RunAs") && ps.contains("exit 121"), ps);
        assertEquals("plain", Strategies.WindowsRunas.cmdQuote("plain"));
        assertEquals("'it''s'", Strategies.WindowsRunas.psQuote("it's"));

        String osa = Strategies.Osascript.script(List.of("/usr/bin/java", "-cp", "/a b/c.jar", "de.yawi.installer.Main"));
        assertEquals("do shell script \"'/usr/bin/java' '-cp' '/a b/c.jar' 'de.yawi.installer.Main'\""
                + " with administrator privileges", osa);
        String quoted = Strategies.Osascript.script(List.of("it's", "say \"hi\""));
        assertTrue(quoted.contains("'it'\\\\''s'"), quoted);
        assertTrue(quoted.contains("\\\"hi\\\""), quoted);
    }
}
