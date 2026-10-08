package de.yawi.installer.core.integrity;

import de.yawi.installer.core.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** E10-S03-T01/T04: paths must stay inside the roots, lexically and physically. */
class PathGuardTest {

    @TempDir
    Path tmp;

    @Test
    void pathsInsideARootAreNormalised() {
        PathGuard guard = new PathGuard(tmp.resolve("dest"), tmp.resolve("work"));
        assertEquals(tmp.resolve("dest/a/b"), guard.confine(tmp.resolve("dest/./a/x/../b")));
        assertEquals(tmp.resolve("work/f"), guard.confine(tmp.resolve("work"), "f"));
        assertEquals(tmp.resolve("dest"), guard.confine(tmp.resolve("dest")), "the root itself is fine");
    }

    @Test
    void dotDotAndAbsoluteEntriesAreRejected() {
        PathGuard guard = new PathGuard(tmp.resolve("dest"));
        PathEscapeException e = assertThrows(PathEscapeException.class,
                () -> guard.confine(tmp.resolve("dest"), "../evil.txt"));
        assertEquals(ErrorCode.MANIFEST_INVALID, e.code());
        assertEquals(tmp.resolve("dest/../evil.txt"), e.path());
        assertThrows(PathEscapeException.class, () -> guard.confine(tmp.resolve("dest"), "/etc/passwd"));
        assertThrows(PathEscapeException.class, () -> guard.confine(tmp.resolve("destination-sibling/x")),
                "prefix match on the name is not enough");
    }

    @Test
    void symlinkedAncestorLeadingOutsideIsRejected() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "symlinks need privileges on Windows");
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.createSymbolicLink(dest.resolve("link"), outside);
        PathGuard guard = new PathGuard(dest);

        assertThrows(PathEscapeException.class, () -> guard.confine(dest.resolve("link/new/file.txt")));
        // A link that stays inside is fine.
        Files.createDirectories(dest.resolve("real"));
        Files.createSymbolicLink(dest.resolve("inner"), dest.resolve("real"));
        assertEquals(dest.resolve("inner/f"), guard.confine(dest.resolve("inner/f")));
    }
}
