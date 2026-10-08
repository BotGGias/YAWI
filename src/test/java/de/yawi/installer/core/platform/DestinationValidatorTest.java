package de.yawi.installer.core.platform;

import de.yawi.installer.core.platform.DestinationValidator.Check;
import de.yawi.installer.core.platform.DestinationValidator.Parsed;
import de.yawi.installer.core.platform.DestinationValidator.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** E06-S02-T04: the destination checks against real temporary directories. */
class DestinationValidatorTest {

    private static final long NO_MIN = -1;

    private final Platform platform = PlatformFactory.current();
    private final DestinationValidator validator = new DestinationValidator(platform);

    @TempDir
    Path tmp;

    private static List<String> keys(Check check) {
        return check.problems().stream().map(Problem::key).toList();
    }

    // --- syntax ------------------------------------------------------------

    @Test
    void blankTextIsAnError() {
        Parsed parsed = validator.parse("   ");
        assertFalse(parsed.isValid());
        assertEquals(DestinationValidator.EMPTY, parsed.problem().key());
        assertTrue(validator.check(null, 0, NO_MIN).isBlocking());
    }

    @Test
    void relativePathIsAnError() {
        Parsed parsed = validator.parse("relative/dir");
        assertFalse(parsed.isValid());
        assertEquals(DestinationValidator.NOT_ABSOLUTE, parsed.problem().key());
    }

    @Test
    void nulCharacterIsNoPath() {
        Parsed parsed = validator.parse(tmp + "/bad\0name");
        assertFalse(parsed.isValid());
        assertEquals(DestinationValidator.INVALID_PATH, parsed.problem().key());
    }

    @Test
    void variablesAndTildeAreExpandedAndThePathNormalised() {
        assumeTrue(platform.os() != OperatingSystem.WINDOWS, "POSIX syntax");
        Parsed parsed = validator.parse("~/a/../b/");
        assertTrue(parsed.isValid());
        assertNull(parsed.problem());
        assertEquals(platform.homeDir().resolve("b"), parsed.path());
        assertEquals(platform.homeDir().resolve("c"), validator.parse("$HOME/c").path());
    }

    // --- file system ---------------------------------------------------------

    @Test
    void emptyExistingDirectoryIsFine() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("empty"));
        Check check = validator.check(dir.toString(), 1024, NO_MIN);
        assertEquals(List.of(), keys(check));
        assertFalse(check.isBlocking());
        assertFalse(check.needsConfirmation());
        assertFalse(check.elevationRequired());
        assertEquals(dir, check.path());
        assertTrue(check.usableBytes() > 0);
    }

    @Test
    void notYetExistingDirectoryIsFineWhenItsParentIsWritable() {
        Check check = validator.check(tmp.resolve("new/deeper").toString(), 1024, NO_MIN);
        assertEquals(List.of(), keys(check));
        assertFalse(check.isBlocking());
    }

    @Test
    void nonEmptyDirectoryWarnsAndNeedsConfirmation() throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("full"));
        Files.writeString(dir.resolve("old.txt"), "x");
        Check check = validator.check(dir.toString(), 1024, NO_MIN);
        assertEquals(List.of(DestinationValidator.NOT_EMPTY), keys(check));
        assertFalse(check.isBlocking(), "a warning must not block");
        assertTrue(check.needsConfirmation());
    }

    @Test
    void regularFileIsAnError() throws Exception {
        Path file = Files.writeString(tmp.resolve("file.txt"), "x");
        Check check = validator.check(file.toString(), 1024, NO_MIN);
        assertEquals(List.of(DestinationValidator.IS_FILE), keys(check));
        assertTrue(check.isBlocking());
    }

    @Test
    void readOnlyDirectoryInUserSpaceIsAnErrorNotAnElevationCase() throws Exception {
        assumeTrue(platform.os() != OperatingSystem.WINDOWS, "POSIX permissions");
        assumeFalse(platform.isElevated(), "root ignores permission bits");
        Path readOnly = Files.createDirectory(tmp.resolve("ro"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-xr-xr-x")));
        try {
            Check check = validator.check(readOnly.resolve("child").toString(), 1024, NO_MIN);
            assertEquals(List.of(DestinationValidator.NOT_WRITABLE), keys(check));
            assertTrue(check.isBlocking());
            assertFalse(check.elevationRequired(), "temp is user space: elevation would not help");
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void protectedSystemPathNeedsElevationInsteadOfFailing() {
        assumeTrue(platform.os() != OperatingSystem.WINDOWS, "unprivileged check on POSIX");
        assumeFalse(platform.isElevated(), "root can write anywhere");
        Check check = validator.check(platform.defaultSystemInstallDir("yawi-installer-test").toString(), 1024, NO_MIN);
        assertTrue(check.elevationRequired());
        assertFalse(check.isBlocking(), keys(check).toString());
    }

    @Test
    void notEnoughSpaceIsAnErrorWithNeededAndUsableBytes() {
        long absurd = Long.MAX_VALUE / 4;
        Check check = validator.check(tmp.toString(), absurd, NO_MIN);
        assertEquals(List.of(DestinationValidator.NOT_ENOUGH_SPACE), keys(check));
        assertTrue(check.isBlocking());
        Problem problem = check.problems().get(0);
        assertEquals(absurd, problem.args()[0]);
        assertEquals(check.usableBytes(), problem.args()[1]);
    }

    @Test
    void minFreeBytesFromTheManifestIsHardToo() {
        Check check = validator.check(tmp.toString(), 1024, Long.MAX_VALUE / 4);
        assertEquals(List.of(DestinationValidator.NOT_ENOUGH_SPACE), keys(check));
        assertEquals(Long.MAX_VALUE / 4, check.problems().get(0).args()[0], "the larger of the two is reported");
    }

    @Test
    void justEnoughSpaceOnlyWarns() {
        long usable = validator.check(tmp.toString(), 0, NO_MIN).usableBytes();
        assumeTrue(usable > 1024, "need a real file system");
        // Fits, but without the 10 % margin.
        long required = (long) (usable / 1.05);
        Check check = validator.check(tmp.toString(), required, NO_MIN);
        assertEquals(List.of(DestinationValidator.SPACE_TIGHT), keys(check));
        assertFalse(check.isBlocking());
    }
}
