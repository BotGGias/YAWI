package de.yawi.installer.core.manifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Loads manifests from the test (and main) classpath. */
public final class TestManifests {

    private TestManifests() {
    }

    /** The full example from manifest-schema.md. */
    public static InstallManifest full() {
        return parse("/manifest/full.xml");
    }

    /** The manifest shipped in src/main/resources. */
    public static InstallManifest bundled() {
        return parse("/installer.xml");
    }

    public static InstallManifest parse(String resource) {
        return new ManifestParser().parse(bytes(resource), origin(resource));
    }

    public static ManifestOrigin origin(String resource) {
        return new ManifestOrigin(ManifestOrigin.Kind.CLASSPATH, resource);
    }

    public static byte[] bytes(String resource) {
        try (InputStream in = TestManifests.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("missing test resource " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
