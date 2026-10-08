package de.yawi.installer.core.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The parts of Platform that touch the file system, run against the real OS. */
class PlatformFileSystemTest {

    private final Platform platform = PlatformFactory.current();

    @TempDir
    Path tmp;

    @Test
    void makeExecutableSetsTheBit() throws Exception {
        assumeTrue(platform.os() != OperatingSystem.WINDOWS, "no executable bit on Windows");
        Path script = Files.writeString(tmp.resolve("run" + platform.scriptExtension()), "#!/bin/sh\nexit 0\n");
        assertFalse(Files.isExecutable(script), "fresh file should not be executable");

        platform.makeExecutable(script);

        assertTrue(Files.isExecutable(script));
    }

    @Test
    void quotedPathSurvivesTheShell() throws Exception {
        assumeTrue(platform.os() != OperatingSystem.WINDOWS, "sh only");
        Path dir = Files.createDirectory(tmp.resolve("it's a \"dir\" with $HOME"));
        Path marker = dir.resolve("marker");

        Process process = new ProcessBuilder(platform.shellCommand("touch " + platform.quote(marker)))
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor() == 0, new String(process.getInputStream().readAllBytes()));

        assertTrue(Files.exists(marker), "quoting must keep the path as one argument");
    }

    // --- E02-S04 ----------------------------------------------------------

    @Test
    void usableSpaceWorksForExistingAndNotYetExistingPaths() throws Exception {
        long existing = platform.usableSpace(tmp);
        long future = platform.usableSpace(tmp.resolve("not/yet/there"));
        assertTrue(existing > 0);
        // same file store, so within a few MiB of each other
        assertTrue(Math.abs(existing - future) < 64L << 20, existing + " vs " + future);
    }

    @Test
    void writableDirectoriesAndCreatableSubdirectories() {
        assertTrue(platform.isWritable(tmp));
        assertTrue(platform.isWritable(tmp.resolve("new/deep/dir")), "creatable below a writable dir");
        try (var files = Files.list(tmp)) {
            assertEquals(0, files.count(), "probe files must be removed");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void regularFileIsNotAWritableDirectory() throws Exception {
        Path file = Files.writeString(tmp.resolve("file.txt"), "x");
        assertFalse(platform.isWritable(file));
    }

    @Test
    void readOnlyDirectoryIsNotWritable() throws Exception {
        assumeTrue(platform.os() != OperatingSystem.WINDOWS, "POSIX permissions");
        assumeFalse(platform.isElevated(), "root ignores permission bits");
        Path readOnly = Files.createDirectory(tmp.resolve("ro"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-xr-xr-x")));
        try {
            assertFalse(platform.isWritable(readOnly));
            assertFalse(platform.isWritable(readOnly.resolve("child")), "not creatable either");
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void userSpaceAndSystemPaths() {
        assertTrue(platform.isUserSpacePath(tmp), tmp + " should be under temp or home");
        assertTrue(platform.isUserSpacePath(platform.homeDir().resolve("anything")));
        assertTrue(platform.isSystemPath(platform.defaultSystemInstallDir("x")));
        assertFalse(platform.isSystemPath(tmp));
        assertFalse(platform.isUserSpacePath(platform.defaultSystemInstallDir("x")));
    }

    @Test
    void elevationIsNeverRequiredInUserSpace() {
        assertFalse(platform.requiresElevation(tmp));
        assertFalse(platform.requiresElevation(tmp.resolve("new")));
    }

    @Test
    void elevationIsRequiredForProtectedSystemPaths() {
        assumeTrue(platform.os() != OperatingSystem.WINDOWS, "unprivileged check on POSIX");
        assumeFalse(platform.isElevated(), "root can write anywhere");
        assertTrue(platform.requiresElevation(Path.of("/usr/local/yawi-installer-test")));
        assertTrue(platform.requiresElevation(platform.defaultSystemInstallDir("yawi-installer-test")));
    }

    @Test
    void isElevatedMatchesTheUser() {
        boolean root = "root".equals(System.getProperty("user.name"));
        assumeTrue(platform.os() != OperatingSystem.WINDOWS, "uid semantics");
        assertEquals(root, platform.isElevated());
        assertEquals(platform.isElevated(), platform.isElevated(), "cached");
    }

    /** Linux and macOS prefixes are plain "/" paths and therefore checkable on any OS. */
    @Test
    void posixSystemPrefixes() {
        Environment env = Environment.of(Map.of(), Map.of("user.home", "/home/me", "java.io.tmpdir", "/tmp"));
        Platform linux = new LinuxPlatform(Architecture.X64, env);
        Platform mac = new MacPlatform(Architecture.X64, env);

        assertTrue(linux.isSystemPath(Path.of("/opt/x")));
        assertTrue(linux.isSystemPath(Path.of("/usr/share/applications")));
        assertTrue(linux.isSystemPath(linux.defaultSystemInstallDir("x")));
        assertFalse(linux.isSystemPath(Path.of("/home/me/opt")));
        assertTrue(linux.isUserSpacePath(Path.of("/home/me/.local/share/x")));

        assertTrue(mac.isSystemPath(Path.of("/Applications/X")));
        assertTrue(mac.isSystemPath(Path.of("/Library/Logs")));
        assertFalse(mac.isSystemPath(Path.of("/Users/me/Applications/X")));
        // paths are normalized before comparing
        assertTrue(linux.isSystemPath(Path.of("/home/me/../../opt/x")));
    }
}
