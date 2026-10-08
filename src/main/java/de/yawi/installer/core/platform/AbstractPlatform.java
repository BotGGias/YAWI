package de.yawi.installer.core.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared state and logic of the three platform implementations.
 *
 * <p>Paths are built as strings with the platform's own separator and wrapped
 * in {@link Path} only at the end, so the logic of every platform can be
 * tested on any machine.
 */
abstract class AbstractPlatform implements Platform {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractPlatform.class);

    private final Architecture arch;
    private final Environment environment;
    private volatile Boolean elevated;

    AbstractPlatform(Architecture arch, Environment environment) {
        this.arch = Objects.requireNonNull(arch, "arch");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public Architecture arch() {
        return arch;
    }

    @Override
    public Environment environment() {
        return environment;
    }

    /** The separator this platform uses in paths. */
    abstract String separator();

    /** Variable syntax of this platform; the first non-null group is the variable name. */
    abstract Pattern variablePattern();

    /** Looks a variable up; Windows adds case-insensitivity. */
    Optional<String> variable(String name) {
        return environment.env(name);
    }

    @Override
    public String expandVariables(String text) {
        Objects.requireNonNull(text, "text");
        Matcher matcher = variablePattern().matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = null;
            for (int g = 1; g <= matcher.groupCount() && name == null; g++) {
                name = matcher.group(g);
            }
            String replacement = variable(name).orElse(matcher.group());
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @Override
    public Path installDir(String manifestDefault, String productId, boolean systemWide) {
        if (manifestDefault != null && !manifestDefault.isBlank()) {
            return Path.of(expandVariables(manifestDefault.trim()));
        }
        return systemWide ? defaultSystemInstallDir(productId) : defaultUserInstallDir(productId);
    }

    @Override
    public Path tempDir() {
        return Path.of(property("java.io.tmpdir").orElse("."));
    }

    // --- E02-S04 space and permissions ------------------------------------

    /** Known system locations of this platform, absolute. */
    abstract List<Path> systemPrefixes();

    /** The uncached elevation check of this platform. */
    abstract boolean detectElevated();

    @Override
    public long usableSpace(Path path) throws IOException {
        return Files.getFileStore(nearestExisting(path)).getUsableSpace();
    }

    @Override
    public boolean isWritable(Path dir) {
        Path target = dir.toAbsolutePath();
        if (Files.isRegularFile(target)) {
            return false;
        }
        Path probeDir = Files.isDirectory(target) ? target : nearestExisting(target);
        if (!Files.isDirectory(probeDir)) {
            return false;
        }
        Path probe = probeDir.resolve(".yawi-installer-probe-" + UUID.randomUUID());
        try {
            Files.createFile(probe);
            return true;
        } catch (IOException | SecurityException e) {
            LOG.debug("{} is not writable: {}", probeDir, e.toString());
            return false;
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException | SecurityException e) {
                LOG.warn("Could not remove probe file {}", probe, e);
            }
        }
    }

    @Override
    public boolean isSystemPath(Path path) {
        return startsWithAny(path, systemPrefixes());
    }

    @Override
    public boolean isUserSpacePath(Path path) {
        return startsWithAny(path, List.of(homeDir(), tempDir()));
    }

    @Override
    public boolean requiresElevation(Path dir) {
        if (isElevated() || isUserSpacePath(dir)) {
            return false;
        }
        return !isWritable(dir);
    }

    @Override
    public boolean isElevated() {
        Boolean value = elevated;
        if (value == null) {
            value = detectElevated();
            elevated = value;
            LOG.debug("Process runs elevated: {}", value);
        }
        return value;
    }

    /** {@code path} itself if it exists, else its closest existing ancestor. */
    static Path nearestExisting(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current == null ? path.toAbsolutePath().getRoot() : current;
    }

    private static boolean startsWithAny(Path path, List<Path> prefixes) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path prefix : prefixes) {
            if (normalized.startsWith(prefix.toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    // --- helpers ---------------------------------------------------------

    Optional<String> env(String name) {
        return environment.env(name).filter(v -> !v.isBlank());
    }

    Optional<String> property(String name) {
        return environment.property(name).filter(v -> !v.isBlank());
    }

    /** {@code user.home}, the last resort for every home lookup. */
    String userHome() {
        return property("user.home").orElse(".");
    }

    /** Joins path segments with this platform's separator. */
    Path join(String base, String... segments) {
        StringBuilder sb = new StringBuilder(stripTrailingSeparator(base));
        for (String segment : segments) {
            sb.append(separator()).append(segment);
        }
        return Path.of(sb.toString());
    }

    private String stripTrailingSeparator(String path) {
        String sep = separator();
        while (path.length() > 1 && path.endsWith(sep)) {
            path = path.substring(0, path.length() - sep.length());
        }
        return path;
    }

    @Override
    public String toString() {
        return os() + "/" + arch;
    }
}
