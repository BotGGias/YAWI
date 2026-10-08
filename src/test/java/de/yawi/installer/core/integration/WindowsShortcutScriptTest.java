package de.yawi.installer.core.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E13-S01-T03: the PowerShell that writes a .lnk. */
class WindowsShortcutScriptTest {

    @Test
    void quotesEveryValueAsASingleQuotedString() {
        String script = WindowsShortcutScript.render(Path.of("C:\\Users\\me\\Desktop\\YOUR INSTALLER.lnk"),
                Path.of("C:\\Games\\su\\yawi.exe"), Path.of("C:\\Games\\su"), Optional.empty());
        assertEquals("$s = (New-Object -ComObject WScript.Shell).CreateShortcut('C:\\Users\\me\\Desktop\\YOUR INSTALLER.lnk'); "
                + "$s.TargetPath = 'C:\\Games\\su\\yawi.exe'; $s.WorkingDirectory = 'C:\\Games\\su'; "
                + "$s.IconLocation = 'C:\\Games\\su\\yawi.exe' + ',0'; $s.Save()", script);
    }

    @Test
    void doublesQuotesInsideValuesAndUsesTheIcoWhenGiven() {
        String script = WindowsShortcutScript.render(Path.of("C:\\x\\O'Brien.lnk"), Path.of("C:\\x\\a.exe"),
                Path.of("C:\\x"), Optional.of(Path.of("C:\\x\\a.ico")));
        assertTrue(script.contains("CreateShortcut('C:\\x\\O''Brien.lnk')"), script);
        assertTrue(script.contains("$s.IconLocation = 'C:\\x\\a.ico' + ',0'"), script);
    }

    @Test
    void theCommandRunsWithoutProfileOrPolicyInTheWay() {
        List<String> command = WindowsShortcutScript.command("x");
        assertEquals(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-Command", "x"), command);
    }
}
