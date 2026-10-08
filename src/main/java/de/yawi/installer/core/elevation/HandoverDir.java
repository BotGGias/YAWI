package de.yawi.installer.core.elevation;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * The private directory the two processes talk through:
 * {@code <tmp>/yawi-installer/elevated-<random>/}, owner-only where the file
 * system supports it. The parent creates every file in it up front, so all
 * of them stay the user's even though the child writes them as root.
 */
public final class HandoverDir {

    public static final String MANIFEST = "manifest.xml";
    public static final String EVENTS = "events.log";
    public static final String RESULT = "result.xml";
    public static final String LOG = "elevated.log";
    public static final String GO = "go";
    public static final String CANCEL = "cancel";

    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-------");

    private HandoverDir() {
    }

    static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /** Creates a fresh directory below {@code parent} (which is created too) with the files the child writes into. */
    public static Path create(Path parent) throws IOException {
        Files.createDirectories(parent);
        Path dir = posix()
                ? Files.createTempDirectory(parent, "elevated-", asAttribute(DIR_PERMS))
                : Files.createTempDirectory(parent, "elevated-");
        for (String name : new String[] {EVENTS, RESULT, LOG}) {
            Path file = dir.resolve(name);
            Files.createFile(file);
            restrictFile(file);
        }
        return dir;
    }

    /** Owner-only rights where supported; a no-op elsewhere. */
    public static void restrictFile(Path file) throws IOException {
        if (posix()) {
            Files.setPosixFilePermissions(file, FILE_PERMS);
        }
    }

    private static FileAttribute<Set<PosixFilePermission>> asAttribute(Set<PosixFilePermission> perms) {
        return PosixFilePermissions.asFileAttribute(perms);
    }

    /** Deletes the directory with everything in it; nothing is thrown, the caller cannot do more anyway. */
    public static void delete(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
