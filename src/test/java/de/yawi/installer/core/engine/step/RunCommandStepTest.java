package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.BundledArtifacts;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.ExecutableStep;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.step.StepTestSupport.Capture;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.PlatformFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** E11-S03-T05: real processes - lots of output, a hang, an exit code, cancel. */
@Timeout(60)
class RunCommandStepTest {

    private static final boolean POSIX = PlatformFactory.current().os() != OperatingSystem.WINDOWS;

    @TempDir
    Path tmp;
    Path dest;
    Capture capture;
    ExecutionContext context;
    InstallManifest manifest;

    @BeforeEach
    void setUp() throws Exception {
        dest = Files.createDirectories(tmp.resolve("dest"));
        manifest = TestManifests.parse("/manifest/engine/run-command.xml");
        capture = new Capture();
        context = StepTestSupport.context(manifest, dest, Map.of("greeting", "hi"), capture,
                new BundledArtifacts(), new CancellationToken());
    }

    private ExecutableStep step(String id) {
        return Steps.DEFAULT.create(manifest.step(id).orElseThrow());
    }

    @Test
    void argumentsEnvironmentAndWorkingDirectoryArrive() {
        step("echo").execute(context);

        assertTrue(capture.output.contains("hi from " + dest), capture.output.toString());
        assertTrue(capture.output.stream().anyMatch(l -> l.endsWith(dest.getFileName().toString())),
                "pwd shows the working dir: " + capture.output);
        if (POSIX) {
            assertTrue(capture.output.contains(dest + "/dir with spaces"),
                    "one <arg> stays one argument, spaces included: " + capture.output);
        }
        assertTrue(capture.output.contains("second"), "every <command> ran: " + capture.output);
        assertTrue(capture.output.get(0).startsWith("$ "), "the command line is echoed first");
    }

    @Test
    void lotsOfOutputDoesNotBlock() {
        assumeTrue(POSIX, "sh loop");
        step("lots").execute(context);
        assertEquals(10_000 + 1, capture.output.size(), "10000 lines plus the echoed command");
        assertEquals("line 9999", capture.output.get(capture.output.size() - 1));
    }

    @Test
    void hangingProcessIsKilledAfterTheTimeout() {
        assumeTrue(POSIX, "sleep");
        long start = System.nanoTime();
        StepFailedException e = assertThrows(StepFailedException.class, () -> step("hang").execute(context));
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertTrue(seconds < 10, "killed, not waited for: " + seconds + " s");
        assertTrue(e.getMessage().contains("timeout after 1 s"), e.getMessage());
        assertEquals(StepFailedException.NO_EXIT_STATUS, e.exitStatus());
        assertTrue(e.detail().orElseThrow().endsWith("\nstarted"), "command line and output so far: " + e.detail());
    }

    @Test
    void unexpectedExitCodeFailsWithStatusAndOutput() {
        StepFailedException e = assertThrows(StepFailedException.class, () -> step("exit3").execute(context));
        assertEquals(3, e.exitStatus());
        assertEquals("exit3", e.stepId());
        assertEquals("step 'exit3' failed: exit code 3 (expected 0) (exit status 3)", e.getMessage());
        assertTrue(e.detail().orElseThrow().startsWith("$ "), "command line first: " + e.detail());
        assertTrue(e.detail().orElseThrow().contains("failing"), e.detail().toString());
        if (POSIX) {
            assertTrue(e.detail().orElseThrow().contains("details"), "stderr is merged: " + e.detail());
            assertTrue(capture.output.stream().noneMatch(l -> l.equals("never")), "later commands do not run");
        }
    }

    @Test
    void expectedNonZeroExitCodeIsFine() {
        step("expect3").execute(context);
    }

    @Test
    void missingExecutableAndWorkingDirectoryAreStepFailures() {
        StepFailedException exe = assertThrows(StepFailedException.class, () -> step("missing-exe").execute(context));
        assertTrue(exe.getMessage().contains("cannot start"), exe.getMessage());
        StepFailedException dir = assertThrows(StepFailedException.class, () -> step("bad-workdir").execute(context));
        assertTrue(dir.getMessage().contains("working directory"), dir.getMessage());
    }

    @Test
    void cancelKillsTheRunningProcess() throws Exception {
        assumeTrue(POSIX, "sleep");
        CancellationToken token = new CancellationToken();
        CountDownLatch started = new CountDownLatch(1);
        ExecutionContext ctx = StepTestSupport.context(manifest, dest, Map.of(), new Capture() {
            @Override
            public void output(String line) {
                if (line.equals("sleeping")) {
                    started.countDown();
                }
            }
        }, new BundledArtifacts(), token);
        Thread canceller = new Thread(() -> {
            try {
                started.await();
                token.cancel();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        canceller.start();

        long start = System.nanoTime();
        assertThrows(CancelledException.class, () -> step("sleep").execute(ctx));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10, "did not wait for sleep 30");
        canceller.join();
    }

    @Test
    void tailKeepsTheLastLinesOnly() {
        RunCommandStep.Tail tail = new RunCommandStep.Tail(3);
        assertNull(tail.text());
        for (int i = 1; i <= 5; i++) {
            tail.add("l" + i);
        }
        assertEquals("[...]\nl3\nl4\nl5", tail.text());
    }

    @Test
    void dryRunShowsResolvedCommands() {
        String text = step("echo").describe(context);
        assertTrue(text.startsWith("run /bin/sh -c ") || text.startsWith("run cmd /c "), text);
        assertTrue(text.contains(dest.toString()), text);
        assertTrue(text.endsWith("(in " + dest + ")"), text);
    }
}
