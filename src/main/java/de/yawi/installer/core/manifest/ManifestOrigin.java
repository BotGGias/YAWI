package de.yawi.installer.core.manifest;

import java.util.Objects;

/**
 * Where a manifest was loaded from, and whether it was signed.
 *
 * <p>Kept on the model because decides how strict the signature check has
 * to be from it: a bundled manifest may be unsigned, one fetched from the
 * network may not - and whether command steps may run at all follows from
 * {@link #isTrusted()}.
 *
 * @param kind      the lookup location that produced the manifest
 * @param location  file path, URL or classpath resource, for logs and the summary page
 * @param signature whether a valid signature was found next to the manifest
 */
public record ManifestOrigin(Kind kind, String location, Signature signature) {

    public enum Kind {
        /** {@code --manifest=<file>} */
        EXPLICIT_FILE,
        /** {@code --manifest=<http(s) url>} */
        EXPLICIT_URL,
        /** {@code installer.xml} next to the application */
        APP_DIR,
        /** {@code /installer.xml} on the classpath, i.e. bundled in the jar */
        CLASSPATH
    }

    /** Outcome of the signature check; an invalid signature never reaches the model. */
    public enum Signature {
        /** No {@code .sig} next to the manifest. */
        UNSIGNED,
        /** A {@code .sig} was found and matches under the embedded key. */
        VERIFIED
    }

    public ManifestOrigin {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(signature, "signature");
    }

    /** An unsigned origin. */
    public ManifestOrigin(Kind kind, String location) {
        this(kind, location, Signature.UNSIGNED);
    }

    /** True if the manifest did not ship with the installer itself. */
    public boolean isRemote() {
        return kind == Kind.EXPLICIT_URL;
    }

    /** True if the manifest's commands may run: it is local, or it is signed and verified. */
    public boolean isTrusted() {
        return !isRemote() || signature == Signature.VERIFIED;
    }

    @Override
    public String toString() {
        return kind + " (" + location + ")" + (signature == Signature.VERIFIED ? ", signed" : "");
    }
}
