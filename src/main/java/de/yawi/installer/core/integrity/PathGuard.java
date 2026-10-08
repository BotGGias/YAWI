package de.yawi.installer.core.integrity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Confines file operations to known roots - the destination and the temp
 * directory. Every target path of a file step and every
 * archive entry goes through {@link #confine} so neither {@code ..} nor a
 * symlink planted earlier can lead outside ("zip slip").
 */
public final class PathGuard {

    private final List<Path> roots;

    public PathGuard(List<Path> roots) {
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("at least one root");
        }
        this.roots = roots.stream().map(r -> r.toAbsolutePath().normalize()).toList();
    }

    public PathGuard(Path... roots) {
        this(List.of(roots));
    }

    public List<Path> roots() {
        return roots;
    }

    /**
     * The normalised absolute form of {@code candidate}, if it lies under a
     * root both lexically and - through its nearest existing ancestor -
     * physically.
     *
     * @throws PathEscapeException otherwise
     */
    public Path confine(Path candidate) {
        Objects.requireNonNull(candidate, "candidate");
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!under(normalized)) {
            throw new PathEscapeException(candidate, "outside " + roots);
        }
        Path existing = nearestExisting(normalized);
        if (existing != null) {
            try {
                Path real = existing.toRealPath();
                if (!real.equals(existing) && !under(real)) {
                    throw new PathEscapeException(candidate, existing + " is a link to " + real);
                }
            } catch (IOException e) {
                throw new PathEscapeException(candidate, "cannot resolve " + existing + ": " + e.getMessage());
            }
        }
        return normalized;
    }

    /** {@code root.resolve(relative)} confined; the usual call for archive entries. */
    public Path confine(Path root, String relative) {
        return confine(root.resolve(relative));
    }

    private boolean under(Path path) {
        for (Path root : roots) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static Path nearestExisting(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        return current;
    }
}
