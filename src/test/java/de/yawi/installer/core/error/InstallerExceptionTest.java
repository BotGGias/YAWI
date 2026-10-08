package de.yawi.installer.core.error;

import de.yawi.installer.core.i18n.Messages;
import de.yawi.installer.core.manifest.ManifestException;
import de.yawi.installer.core.manifest.ManifestProblem;
import de.yawi.installer.core.platform.UnsupportedPlatformException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E17-S01: the error catalogue, the texts behind it and the translation at the boundary. */
class InstallerExceptionTest {

    private final Messages messages = Messages.load();

    /** The contract with the silent mode: fixed numbers, no gaps, no duplicates. */
    @Test
    void exitCodesFollowTheCatalogue() {
        assertEquals(1, ErrorCode.GENERAL.exitCode());
        assertEquals(2, ErrorCode.INVALID_ARGUMENTS.exitCode());
        assertEquals(3, ErrorCode.MANIFEST_INVALID.exitCode());
        assertEquals(4, ErrorCode.INTEGRITY_FAILED.exitCode());
        assertEquals(5, ErrorCode.PERMISSION_DENIED.exitCode());
        assertEquals(6, ErrorCode.NOT_ENOUGH_SPACE.exitCode());
        assertEquals(7, ErrorCode.CANCELLED.exitCode());
        assertEquals(8, ErrorCode.SOURCE_UNAVAILABLE.exitCode());
        assertEquals(9, ErrorCode.STEP_FAILED.exitCode());
        assertEquals(10, ErrorCode.UNSUPPORTED_PLATFORM.exitCode());
        assertEquals(11, ErrorCode.NO_INSTALLATION.exitCode());
        assertEquals(12, ErrorCode.WAIT_TIMEOUT.exitCode());

        Set<Integer> seen = new HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(code.exitCode() > 0, code + ": 0 is success");
            assertTrue(seen.add(code.exitCode()), code + ": exit code used twice");
        }
    }

    /** Every code has a text and a hint in the base bundle and in German. */
    @Test
    void everyCodeHasTextAndHintInTheBundles() {
        List<String> missing = Arrays.stream(ErrorCode.values())
                .flatMap(code -> java.util.stream.Stream.of(code.messageKey(), code.hintKey()))
                .filter(key -> !messages.hasKey(key)
                        || messages.missingKeys(Locale.GERMAN).contains(key))
                .toList();
        assertEquals(List.of(), missing);
    }

    @Test
    void userTextAndHintAreResolvedInTheGivenLanguage() {
        PermissionDeniedException e = new PermissionDeniedException(Path.of("/opt/x"));

        assertEquals("You have no permission to write to /opt/x.", e.userMessage(messages, Locale.ENGLISH));
        assertEquals("Sie haben keine Schreibrechte für /opt/x.", e.userMessage(messages, Locale.GERMAN));
        assertTrue(e.hint(messages, Locale.GERMAN).contains("Für alle Benutzer"));
        assertEquals("permission denied: /opt/x", e.getMessage(), "technical text for the log");
        assertEquals(5, e.exitCode());
        assertEquals(Path.of("/opt/x"), e.path());
    }

    @Test
    void everySubclassRendersWithoutMarkers() {
        List<InstallerException> all = List.of(
                new InstallerException(ErrorCode.GENERAL, "boom", "boom"),
                new InvalidArgumentsException("--bogus", "unknown option"),
                new ManifestException("bad", List.of(ManifestProblem.error("component 'x'", "unknown"))),
                new IntegrityException("data.zip", "sha256 mismatch"),
                new PermissionDeniedException(Path.of("/opt")),
                new InsufficientSpaceException(Path.of("/opt"), 2L << 30, 1L << 30),
                new CancelledException(),
                new SourceUnavailableException("game-data", "all providers failed", null),
                new StepFailedException("run-setup", "exit code 3", 3, "stderr...", null),
                new UnsupportedPlatformException("Plan 9", "mips"),
                new NoInstallationException(Path.of("/opt/app"), "no record", null),
                new WaitTimeoutException(List.of(4711L, 4712L), java.time.Duration.ofSeconds(60)),
                new ElevationUnavailableException("no pkexec, no sudo askpass, no terminal", "stderr..."));
        Set<ErrorCode> covered = new HashSet<>();
        for (InstallerException e : all) {
            covered.add(e.code());
            for (Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                String text = e.userMessage(messages, locale);
                String hint = e.hint(messages, locale);
                assertFalse(text.startsWith("!") || text.contains("{"), e.code() + ": " + text);
                assertFalse(hint.startsWith("!"), e.code() + ": " + hint);
            }
        }
        assertEquals(Set.of(ErrorCode.values()), covered, "every code has a class under test");
    }

    @Test
    void sizesGoIntoTheSpaceTextPreFormatted() {
        InsufficientSpaceException e = new InsufficientSpaceException(Path.of("/data"), 2L << 30, 1L << 30);
        assertEquals("Not enough free space at /data: 2 GiB required, 1 GiB available.",
                e.userMessage(messages, Locale.ENGLISH));
        assertEquals(2L << 30, e.requiredBytes());
        assertEquals(1L << 30, e.availableBytes());
    }

    @Test
    void manifestExceptionKeepsProblemsAndPutsThemIntoTheDetail() {
        ManifestProblem problem = ManifestProblem.error(12, 5, "unexpected element");
        ManifestException e = new ManifestException("Manifest rejected", List.of(problem));

        assertEquals(ErrorCode.MANIFEST_INVALID, e.code());
        assertEquals(List.of(problem), e.getProblems());
        assertTrue(e.getMessage().contains("line 12:5 - unexpected element"), "log text lists problems");
        assertTrue(e.detail().orElseThrow().contains("unexpected element"), "detail pane lists problems");
        assertEquals("The installer's configuration (manifest) is invalid.", e.userMessage(messages, Locale.ENGLISH));
    }

    @Test
    void stepFailureCarriesIdStatusAndDetail() {
        StepFailedException e = new StepFailedException("copy-files", "target vanished", StepFailedException.NO_EXIT_STATUS, null, null);
        assertEquals("copy-files", e.stepId());
        assertEquals(StepFailedException.NO_EXIT_STATUS, e.exitStatus());
        assertTrue(e.detail().isEmpty());
        assertEquals("Installation step copy-files failed: target vanished", e.userMessage(messages, Locale.ENGLISH));

        StepFailedException withStatus = new StepFailedException("run", "exit code 3", 3, "out", null);
        assertEquals("step 'run' failed: exit code 3 (exit status 3)", withStatus.getMessage());
        assertEquals("out", withStatus.detail().orElseThrow());
    }

    @Test
    void wrapPassesInstallerExceptionsThroughAndBoxesTheRest() {
        CancelledException cancelled = new CancelledException();
        assertSame(cancelled, InstallerException.wrap(cancelled));

        IllegalStateException cause = new IllegalStateException("kaputt");
        InstallerException wrapped = InstallerException.wrap(cause);
        assertEquals(ErrorCode.GENERAL, wrapped.code());
        assertSame(cause, wrapped.getCause());
        assertEquals("An unexpected error occurred: kaputt", wrapped.userMessage(messages, Locale.ENGLISH));

        InstallerException noMessage = InstallerException.wrap(new NullPointerException());
        assertEquals("An unexpected error occurred: NullPointerException", noMessage.userMessage(messages, Locale.ENGLISH));
    }

    @Test
    void ioExceptionsAreTranslatedAtTheBoundary() {
        Path context = Path.of("/target/file");

        InstallerException denied = Errors.fromIo(new AccessDeniedException("/target/file"), context);
        assertInstanceOf(PermissionDeniedException.class, denied);
        assertEquals(context, ((PermissionDeniedException) denied).path());

        InstallerException full = Errors.fromIo(new FileSystemException("/target/file", null, "No space left on device"), context);
        assertInstanceOf(InsufficientSpaceException.class, full);
        assertEquals("Not enough free space at /target/file: 0 B required, ? available.",
                full.userMessage(messages, Locale.ENGLISH));

        InstallerException other = Errors.fromIo(new NoSuchFileException("/target/file"), context);
        assertEquals(ErrorCode.GENERAL, other.code());
        assertTrue(other.getMessage().startsWith("I/O error at /target/file"), other.getMessage());
        assertInstanceOf(IOException.class, other.getCause());
    }
}
