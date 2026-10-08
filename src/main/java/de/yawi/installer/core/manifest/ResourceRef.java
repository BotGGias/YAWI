package de.yawi.installer.core.manifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Opens the {@code classpath:/...}-or-file references the manifest uses for
 * the product icon, the license text and bundled payloads.
 *
 * <p>Anything not starting with {@link #CLASSPATH_PREFIX} is a file path.
 * Relative paths resolve against the working directory; E07 decides whether
 * they should resolve against the manifest location instead.
 */
public final class ResourceRef {

    public static final String CLASSPATH_PREFIX = "classpath:";

    private ResourceRef() {
    }

    public static boolean isClasspath(String ref) {
        return ref.startsWith(CLASSPATH_PREFIX);
    }

    /**
     * @throws IOException if the resource does not exist or cannot be read
     */
    public static InputStream open(String ref) throws IOException {
        Objects.requireNonNull(ref, "ref");
        if (isClasspath(ref)) {
            String resource = ref.substring(CLASSPATH_PREFIX.length());
            if (!resource.startsWith("/")) {
                resource = "/" + resource;
            }
            InputStream in = ResourceRef.class.getResourceAsStream(resource);
            if (in == null) {
                throw new NoSuchFileException(ref);
            }
            return in;
        }
        return Files.newInputStream(Path.of(ref));
    }

    /** The referenced resource as UTF-8 text. */
    public static String readText(String ref) throws IOException {
        try (InputStream in = open(ref)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
