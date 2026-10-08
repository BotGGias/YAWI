package de.yawi.installer.core.elevation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfCommandTest {

    @Test
    void appImageLauncherWinsOverEverything() {
        Properties p = new Properties();
        p.setProperty("jpackage.app-path", "/opt/yawi-installer/bin/yawi-installer");
        p.setProperty("java.class.path", "x.jar");
        assertEquals(List.of("/opt/yawi-installer/bin/yawi-installer"), SelfCommand.resolve(p, Optional.empty()));
    }

    @Test
    void classPathAndModulePathFormsAreBuiltFromTheProperties() {
        Properties p = new Properties();
        p.setProperty("java.home", "/jdk");
        p.setProperty("java.class.path", "lib/a.jar:lib/b.jar");
        List<String> cp = SelfCommand.resolve(p, Optional.empty());
        assertEquals(Path.of("/jdk/bin/java").toAbsolutePath().toString(), cp.get(0));
        assertEquals("-Djava.awt.headless=true", cp.get(1));
        assertEquals("-cp", cp.get(2));
        assertTrue(cp.get(3).contains(Path.of("lib/a.jar").toAbsolutePath().toString()), cp.get(3));
        assertEquals(SelfCommand.MAIN_CLASS, cp.get(4));

        p.setProperty("jdk.module.main", "de.yawi.installer");
        p.setProperty("jdk.module.path", "target/classes:lib");
        p.setProperty("java.class.path", "");
        List<String> mp = SelfCommand.resolve(p, Optional.empty());
        assertEquals("--module-path", mp.get(2));
        assertEquals("-m", mp.get(4));
        assertEquals("de.yawi.installer/" + SelfCommand.MAIN_CLASS, mp.get(5));
    }

    @Test
    void theResolvedCommandStartsThisInstaller() throws Exception {
        List<String> command = new ArrayList<>(SelfCommand.resolve());
        command.add("--version");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "installer did not end");
        assertEquals(0, process.exitValue(), out);
        assertTrue(out.contains("yawi-installer"), out);
    }
}
