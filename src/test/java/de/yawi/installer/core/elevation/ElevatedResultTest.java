package de.yawi.installer.core.elevation;

import de.yawi.installer.core.engine.Engine;
import de.yawi.installer.core.engine.Rollback;
import de.yawi.installer.core.engine.StepOutcome;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.InsufficientSpaceException;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.TestManifests;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElevatedResultTest {

    @TempDir
    Path tmp;

    private static final InstallManifest MANIFEST = TestManifests.parse("/manifest/engine/elevated.xml");

    private static InstallStep step(String id) {
        return MANIFEST.step(id).orElseThrow();
    }

    private ElevatedResult roundTrip(ElevatedResult result) throws IOException {
        Path file = tmp.resolve("result.xml");
        result.write(file);
        return ElevatedResult.read(file).orElseThrow();
    }

    @Test
    void aStepFailureComesBackAsAStepFailedExceptionWithDetail() throws IOException {
        StepFailedException failure = new StepFailedException("service", "exit code 3 (expected 0)", 3,
                "$ /bin/sh -c ...\nline <two> & \"three\"", null);
        Engine.Result result = new Engine.Result(false, List.of(
                new Engine.StepReport(step("write"), StepOutcome.DONE, Optional.empty()),
                new Engine.StepReport(step("service"), StepOutcome.FAILED, Optional.of(failure))),
                Optional.of(failure)).withRollback(new Rollback.Report(1, 2, List.of("x"), List.of("f1", "f2"),
                List.of(Path.of("/left"))));
        ElevatedResult remote = roundTrip(ElevatedResult.of(result));
        assertEquals(9, remote.exitCode());
        assertEquals(2, remote.reports().size());
        assertEquals(StepOutcome.FAILED, remote.reports().get(1).outcome());
        StepFailedException back = assertInstanceOf(StepFailedException.class, remote.failure().orElseThrow().toException());
        assertEquals("service", back.stepId());
        assertEquals(3, back.exitStatus());
        assertEquals(failure.getMessage(), back.getMessage());
        assertEquals(failure.detail(), back.detail(), "multi-line detail survives");
        assertEquals(List.of("service", "exit code 3 (expected 0)"), List.of(back.userArgs()));
        Rollback.Report rollback = remote.rollback().orElseThrow();
        assertEquals(1, rollback.restored());
        assertEquals(List.of("f1", "f2"), rollback.failures());
        assertEquals(List.of(Path.of("/left")), rollback.leftBehind());
    }

    @Test
    void cancellationAndOtherCodesKeepTheirCodeAndText() throws IOException {
        ElevatedResult cancelled = roundTrip(ElevatedResult.of(new Engine.Result(false, List.of(),
                Optional.of(new CancelledException()))));
        assertTrue(cancelled.cancelled());
        assertEquals(7, cancelled.exitCode());
        assertInstanceOf(CancelledException.class, cancelled.failure().orElseThrow().toException());

        ElevatedResult space = roundTrip(ElevatedResult.failed(new InsufficientSpaceException(tmp, 10, 5)));
        InstallerException back = space.failure().orElseThrow().toException();
        assertEquals(ErrorCode.NOT_ENOUGH_SPACE, back.code());
        assertEquals("error.space", back.messageKey());
        assertEquals(3, back.userArgs().length);

        ElevatedResult ok = roundTrip(ElevatedResult.of(new Engine.Result(true, List.of(
                new Engine.StepReport(step("mk"), StepOutcome.DONE, Optional.empty())), Optional.empty())));
        assertTrue(ok.succeeded());
        assertEquals(0, ok.exitCode());
        assertEquals(Optional.empty(), ok.rollback());
    }

    @Test
    void anEmptyFileMeansNoResult() throws IOException {
        Path file = tmp.resolve("empty.xml");
        Files.createFile(file);
        assertEquals(Optional.empty(), ElevatedResult.read(file));
        assertEquals(Optional.empty(), ElevatedResult.read(tmp.resolve("missing.xml")));
    }
}
