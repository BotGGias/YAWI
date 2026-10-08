package de.yawi.installer.core.platform;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Finds an executable on the platform's {@code PATH} (menu cache, elevation tools). */
public final class PathLookup {

    private PathLookup() {
    }

    /** The first executable named {@code tool} in a {@code PATH} directory, or empty. */
    public static Optional<Path> find(Platform platform, String tool) {
        String path = platform.environment().env("PATH").orElse("");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, tool);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
