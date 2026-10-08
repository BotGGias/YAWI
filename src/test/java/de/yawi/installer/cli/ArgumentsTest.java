package de.yawi.installer.cli;

import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InvalidArgumentsException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** the parser accepts exactly the documented options and nothing else. */
class ArgumentsTest {

    @Test
    void noArgumentsMeansTheWizard() {
        Arguments a = Arguments.parse();
        assertFalse(a.headless());
        assertFalse(a.silent());
        assertEquals(List.of(), a.components());
        assertEquals(Map.of(), a.inputs());
    }

    @Test
    void theUpdateContractsCallIsParsedCompletely() {
        Arguments a = Arguments.parse("--silent", "--update", "--dest=/opt/su", "--cache=/data/updates/1.2.3",
                "--wait-pid=4711", "--wait-pid=4712", "--relaunch", "--result=/data/updates/1.2.3/result.json",
                "--log=/data/logs/installer-1.log", "--lang=de", "--accept-license");
        assertTrue(a.headless());
        assertTrue(a.silent());
        assertTrue(a.update());
        assertEquals("/opt/su", a.dest());
        assertEquals("/data/updates/1.2.3", a.cache());
        assertEquals(List.of(4711L, 4712L), a.waitPids());
        assertTrue(a.relaunch());
        assertEquals("/data/updates/1.2.3/result.json", a.result());
        assertEquals("/data/logs/installer-1.log", a.log());
        assertEquals("de", a.lang());
        assertTrue(a.acceptLicense());
        assertFalse(a.dryRun());
        assertFalse(a.quiet());
        assertFalse(a.json());
    }

    @Test
    void componentsInputsAndOutputMode() {
        Arguments a = Arguments.parse("--silent", "--components=core, server,,", "--input.serverPort=5000",
                "--input.Name=x=y", "--output=json", "--quiet", "--dry-run", "--all-users", "--manifest=/m.xml");
        assertEquals(List.of("core", "server"), a.components());
        assertEquals(Map.of("serverPort", "5000", "Name", "x=y"), a.inputs(), "ids keep their case, values may hold =");
        assertTrue(a.json());
        assertTrue(a.quiet());
        assertTrue(a.dryRun());
        assertTrue(a.allUsers());
        assertEquals("/m.xml", a.manifest());
        assertFalse(Arguments.parse("--output=text").json());
    }

    @Test
    void helpAndVersionAreHeadlessWithoutSilent() {
        assertTrue(Arguments.parse("--help").headless());
        assertTrue(Arguments.parse("--version").headless());
        assertTrue(Arguments.parse("--HELP").help(), "option names are case-insensitive");
    }

    @Test
    void unknownOptionsAndBadValuesAreArgumentErrors() {
        for (String[] bad : List.of(
                new String[] {"--frobnicate"},
                new String[] {"install"},
                new String[] {"--dest"},
                new String[] {"--dest="},
                new String[] {"--silent=yes"},
                new String[] {"--wait-pid=abc"},
                new String[] {"--wait-pid=-1"},
                new String[] {"--output=xml"},
                new String[] {"--input.=x"},
                new String[] {"--input.port"},
                new String[] {"--update"})) {
            InvalidArgumentsException e = assertThrows(InvalidArgumentsException.class, () -> Arguments.parse(bad),
                    String.join(" ", bad));
            assertEquals(ErrorCode.INVALID_ARGUMENTS, e.code());
            assertEquals(2, e.exitCode());
        }
    }

    @Test
    void configIsSilentOnlyAndNotForUpdates() {
        assertEquals("/a.xml", Arguments.parse("--silent", "--config=/a.xml").config());
        InvalidArgumentsException wizard = assertThrows(InvalidArgumentsException.class,
                () -> Arguments.parse("--config=/a.xml"));
        assertTrue(wizard.getMessage().contains("--silent"), wizard.getMessage());
        InvalidArgumentsException update = assertThrows(InvalidArgumentsException.class,
                () -> Arguments.parse("--silent", "--update", "--config=/a.xml"));
        assertTrue(update.getMessage().contains("--update"), update.getMessage());
    }

    @Test
    void repairIsSilentOnlyAndKeepsItsInputsButNotTheOtherSelectionOptions() {
        Arguments ok = Arguments.parse("--silent", "--repair", "--dest=/opt/su", "--input.port=5000");
        assertTrue(ok.repair());
        assertEquals("5000", ok.inputs().get("port"));

        assertTrue(assertThrows(InvalidArgumentsException.class, () -> Arguments.parse("--repair"))
                .getMessage().contains("--silent"));
        for (String clash : List.of("--update", "--uninstall", "--config=/a.xml", "--components=core",
                "--accept-license", "--all-users")) {
            InvalidArgumentsException e = assertThrows(InvalidArgumentsException.class,
                    () -> Arguments.parse("--silent", "--repair", clash));
            assertTrue(e.getMessage().contains("--repair"), e.getMessage());
        }
    }

    /** E14-S03: an uninstall takes everything from the record; options that would be ignored are refused. */
    @Test
    void noAssociationsAndNoPathAreFlags() {
        Arguments a = Arguments.parse("--silent", "--no-associations", "--no-path");
        assertTrue(a.noAssociations());
        assertTrue(a.noPath());
        Arguments none = Arguments.parse("--silent");
        assertFalse(none.noAssociations());
        assertFalse(none.noPath());
    }

    @Test
    void uninstallAndPurge() {
        Arguments a = Arguments.parse("--silent", "--uninstall", "--dest=/opt/app", "--purge", "--dry-run",
                "--wait-pid=42", "--result=/tmp/r.json");
        assertTrue(a.uninstall() && a.purge() && a.dryRun());
        assertEquals("/opt/app", a.dest());
        assertFalse(Arguments.parse("--silent", "--uninstall").purge());

        for (String[] bad : List.of(
                new String[] {"--uninstall"},
                new String[] {"--silent", "--purge"},
                new String[] {"--silent", "--uninstall", "--update"},
                new String[] {"--silent", "--uninstall", "--config=/a.xml"},
                new String[] {"--silent", "--uninstall", "--components=core"},
                new String[] {"--silent", "--uninstall", "--input.port=1"},
                new String[] {"--silent", "--uninstall", "--cache=/c"},
                new String[] {"--silent", "--uninstall", "--relaunch"},
                new String[] {"--silent", "--uninstall", "--accept-license"})) {
            InvalidArgumentsException e = assertThrows(InvalidArgumentsException.class, () -> Arguments.parse(bad),
                    String.join(" ", bad));
            assertEquals(2, e.exitCode());
        }
    }
}
