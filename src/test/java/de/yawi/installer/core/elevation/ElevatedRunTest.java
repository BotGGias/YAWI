package de.yawi.installer.core.elevation;

import de.yawi.installer.core.download.HttpDownloader;
import de.yawi.installer.core.download.SourceResolver;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.Engine;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.FailurePrompt;
import de.yawi.installer.core.engine.InstallRunner;
import de.yawi.installer.core.engine.ProgressListener;
import de.yawi.installer.core.engine.StepOutcome;
import de.yawi.installer.core.error.ElevationUnavailableException;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.TestPlatforms;
import de.yawi.installer.core.state.RecordWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The whole hand-over between the parent and a second installer process,
 * without a rights prompt ({@link Elevation#direct()}): the child is a real
 * process started through {@link SelfCommand}.
 */
class ElevatedRunTest {

    @TempDir
    Path tmp;

    private Platform platform;
    private final InstallManifest manifest = TestManifests.parse("/manifest/engine/elevated.xml");

    /** Every event the parent saw, as text. */
    static final class Events implements ProgressListener {
        final List<String> lines = new ArrayList<>();
        final List<Double> overall = new ArrayList<>();
        boolean elevationRequested;
        Runnable onStep = () -> { };

        @Override
        public synchronized void stepStarted(InstallStep step, int index, int count) {
            lines.add("start " + step.id() + " " + index + "/" + count);
            onStep.run();
        }

        @Override
        public synchronized void stepFinished(InstallStep step, StepOutcome outcome) {
            lines.add("end " + step.id() + " " + outcome);
        }

        @Override
        public synchronized void overall(double fraction) {
            overall.add(fraction);
        }

        @Override
        public synchronized void output(String line) {
            lines.add("| " + line);
        }

        @Override
        public void elevationRequested() {
            elevationRequested = true;
        }

        @Override
        public synchronized void rollbackStarted(int total) {
            lines.add("rollback " + total);
        }
    }

    @BeforeEach
    void scratch() {
        platform = TestPlatforms.scratch(tmp.resolve("home"));
        assumeFalse(platform.isElevated(), "as root nothing needs elevating");
    }

    private InstallRunner.Request request(Path dest, Set<String> components) {
        return InstallRunner.Request.of(manifest, platform, dest, components, Map.of("adminPassword", "hunter2"),
                Map.of(), Optional.empty());
    }

    private static InstallRunner runner(Elevation elevation) {
        return new InstallRunner(new SourceResolver(new HttpDownloader()), Optional.empty(), elevation);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).strip();
    }

    private static boolean noHandoverLeft(Platform platform) throws IOException {
        Path work = ExecutionContext.workDir(platform);
        if (!Files.isDirectory(work)) {
            return true;
        }
        try (Stream<Path> list = Files.list(work)) {
            return list.noneMatch(p -> p.getFileName().toString().startsWith("elevated-"));
        }
    }

    @Test
    void partialRunExecutesTheElevatedBlockAfterTheNormalStepsAndRecordsIt() throws Exception {
        Path dest = tmp.resolve("partial");
        Events events = new Events();
        Engine.Result result = runner(Elevation.direct()).run(request(dest, Set.of("core")), events,
                FailurePrompt.ABORT_ALWAYS, new CancellationToken());
        assertTrue(result.succeeded(), () -> result.failure().map(Throwable::getMessage).orElse("?") + " " + events.lines);
        assertEquals(3, result.reports().size());
        assertEquals("normal", read(dest.resolve("normal.txt")));
        assertEquals("hunter2", read(dest.resolve("service.txt")), "the elevated step got the secret input");

        String record = Files.readString(RecordWriter.defaultFile(dest));
        int write = record.indexOf("<step id=\"write\" event=\"finished\"");
        int service = record.indexOf("<step id=\"service\" event=\"started\"/>");
        int serviceDone = record.indexOf("<step id=\"service\" event=\"finished\" outcome=\"DONE\"/>");
        int finished = record.indexOf("<finished at=");
        assertTrue(write > 0 && service > write && serviceDone > service && finished > serviceDone, record);
        assertTrue(record.endsWith("</record>\n"), record);

        assertTrue(events.lines.contains("start service 2/3"), "child's step placed into the whole: " + events.lines);
        assertTrue(events.lines.contains("end service DONE"), events.lines.toString());
        assertEquals(1.0, events.overall.get(events.overall.size() - 1), 1e-9);
        assertTrue(events.overall.stream().allMatch(f -> f >= 0 && f <= 1.0));
        assertFalse(events.elevationRequested, "the direct strategy shows no prompt");
        assertTrue(noHandoverLeft(platform), "hand-over directory cleaned up");
        assertTrue(Files.isRegularFile(LogSetup.currentLogFile().resolveSibling(ElevatedRun.COPIED_LOG_NAME)));
    }

    @Test
    void failingElevatedStepIsUndoneByTheChildAndTheRestByTheParent() throws Exception {
        Path dest = tmp.resolve("failing");
        Events events = new Events();
        Engine.Result result = runner(Elevation.direct()).run(request(dest, Set.of("core", "broken")), events,
                FailurePrompt.ABORT_ALWAYS, new CancellationToken());
        assertFalse(result.succeeded());
        StepFailedException failure = assertInstanceOf(StepFailedException.class, result.failure().orElseThrow());
        assertEquals("boom", failure.stepId());
        assertEquals(3, failure.exitStatus());
        assertEquals(4, result.reports().size(), result.reports().toString());
        assertEquals(StepOutcome.FAILED, result.reports().get(3).outcome());

        assertTrue(Files.notExists(dest.resolve("service.txt")), "child's reverse command ran");
        assertEquals("undone", read(dest.resolve("service-undone.txt")));
        assertTrue(Files.notExists(dest.resolve("made")), "parent's rollback removed the directory");
        assertTrue(Files.isRegularFile(dest.resolve("normal.txt")), "a command without <rollback> is not undone");
        assertTrue(result.rollback().isPresent());
        assertTrue(events.lines.stream().filter(l -> l.startsWith("rollback ")).count() >= 2,
                "both rollbacks reported: " + events.lines);
        assertTrue(noHandoverLeft(platform));
    }

    @Test
    void wholeRunLetsTheChildOwnTheRecord() throws Exception {
        Path dest = tmp.resolve("whole");
        Events events = new Events();
        Engine.Result result = runner(Elevation.direct().forcing(ElevationNeed.Mode.WHOLE))
                .run(request(dest, Set.of("core")), events, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
        assertTrue(result.succeeded(), () -> result.failure().map(Throwable::getMessage).orElse("?") + " " + events.lines);
        assertEquals(List.of("mk", "write", "service"), result.reports().stream().map(r -> r.step().id()).toList());
        assertEquals("hunter2", read(dest.resolve("service.txt")));
        String record = Files.readString(RecordWriter.defaultFile(dest));
        assertTrue(record.contains("<dir path=\"made\"/>") && record.contains("<finished at="), record);
        assertTrue(events.lines.contains("start mk 0/3") && events.lines.contains("start service 2/3"), events.lines.toString());
        assertTrue(noHandoverLeft(platform));
    }

    @Test
    void cancellingWhileTheChildRunsRollsBackOnBothSides() throws Exception {
        Path dest = tmp.resolve("cancel");
        Events events = new Events();
        CancellationToken token = new CancellationToken();
        // Cancel as soon as the child's first step starts: its engine stops at the next checkpoint.
        events.onStep = () -> {
            if (events.lines.get(events.lines.size() - 1).startsWith("start write")) {
                token.cancel();
            }
        };
        Engine.Result result = runner(Elevation.direct().forcing(ElevationNeed.Mode.WHOLE))
                .run(request(dest, Set.of("core")), events, FailurePrompt.ABORT_ALWAYS, token);
        assertTrue(result.isCancelled(), result.failure().map(Throwable::toString).orElse("?"));
        assertTrue(Files.notExists(dest.resolve("service.txt")), "never reached, or undone");
        assertTrue(result.rollback().isPresent());
        assertTrue(noHandoverLeft(platform));
    }

    @Test
    void cancellingBeforeTheChildsTurnEndsItQuietly() throws Exception {
        Path dest = tmp.resolve("cancel-early");
        Events events = new Events();
        CancellationToken token = new CancellationToken();
        events.onStep = () -> {
            if (events.lines.get(events.lines.size() - 1).startsWith("start write")) {
                token.cancel();
            }
        };
        Engine.Result result = runner(Elevation.direct()).run(request(dest, Set.of("core")), events,
                FailurePrompt.ABORT_ALWAYS, token);
        assertTrue(result.isCancelled());
        assertTrue(Files.notExists(dest.resolve("service.txt")));
        assertTrue(noHandoverLeft(platform));
    }

    @Test
    void withoutAnyStrategyTheRunFailsWithExit13BeforeWritingAnything() {
        Path dest = tmp.resolve("none");
        ElevationUnavailableException e = assertThrows(ElevationUnavailableException.class, () ->
                runner(Elevation.none()).run(request(dest, Set.of("core")), ProgressListener.NOOP,
                        FailurePrompt.ABORT_ALWAYS, new CancellationToken()));
        assertEquals(13, e.exitCode());
        assertTrue(Files.notExists(dest));
    }
}
