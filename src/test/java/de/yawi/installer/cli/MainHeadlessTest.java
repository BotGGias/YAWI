package de.yawi.installer.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the entry point in a fresh JVM without a display. If the
 * silent path ever touched the JavaFX toolkit, these would fail with
 * "Unable to open DISPLAY" or a HeadlessException.
 */
class MainHeadlessTest {

    record Run(int exit, String out, String err) {
    }

    @Test
    void helpWorksWithoutADisplay() throws Exception {
        Run run = run("--help");
        assertEquals(0, run.exit(), run.err());
        assertTrue(run.out().contains("--update") && run.out().contains("--uninstall"), run.out());
        assertTrue(run.out().contains("--wait-pid"), run.out());
    }

    @Test
    void versionUsesTheBundledManifest() throws Exception {
        Run run = run("--version");
        assertEquals(0, run.exit(), run.err());
        assertTrue(run.out().startsWith("yawi-installer your-product "), run.out());
    }

    @Test
    void unknownOptionIsExit2WithAHintNotAStackTrace() throws Exception {
        Run run = run("--silent", "--frobnicate");
        assertEquals(2, run.exit(), run.err());
        assertTrue(run.err().contains("--help"), run.err());
        assertTrue(!run.err().contains("Exception"), run.err());
    }

    /** The elevated child's entry is internal: not in the help, and a bad hand-over directory is a plain exit 1. */
    @Test
    void elevatedRunOptionIsHiddenAndFailsCleanly() throws Exception {
        Run help = run("--help");
        assertEquals(0, help.exit(), help.err());
        assertTrue(!help.out().contains("elevated-run"), help.out());

        Run bad = run("--elevated-run=" + java.nio.file.Files.createTempDirectory("su-no-plan"));
        assertEquals(1, bad.exit(), bad.err());
        assertTrue(!bad.err().contains("Exception"), bad.err());
    }

    private static Run run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("de.yawi.installer.Main");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().remove("DISPLAY");
        builder.environment().remove("WAYLAND_DISPLAY");
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "installer did not end");
        return new Run(process.exitValue(), out, err);
    }
}
