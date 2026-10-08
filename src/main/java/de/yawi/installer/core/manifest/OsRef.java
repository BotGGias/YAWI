package de.yawi.installer.core.manifest;

import de.yawi.installer.core.platform.OperatingSystem;

import java.util.Objects;

/**
 * A {@code <uses>}/{@code <step>} reference, optionally limited to one OS.
 *
 * @param os the platform this reference applies to, or {@code null} for every OS
 */
public record OsRef(String ref, OperatingSystem os) {

    public OsRef {
        Objects.requireNonNull(ref, "ref");
    }

    /** True if this reference applies on {@code os}. */
    public boolean appliesTo(OperatingSystem os) {
        return this.os == null || this.os == os;
    }
}
