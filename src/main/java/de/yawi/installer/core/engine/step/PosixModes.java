package de.yawi.installer.core.engine.step;

import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/** Octal POSIX modes ({@code 755}) to and from permission sets; shared by the chmod step and the rollback. */
public final class PosixModes {

    private static final PosixFilePermission[] ORDER = {
            PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_READ,
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ};

    private PosixModes() {
    }

    /** {@code 755} → rwxr-xr-x; a leading 0 (0755) is accepted, setuid bits are ignored. */
    public static Set<PosixFilePermission> permissions(String mode) {
        int bits = Integer.parseInt(mode, 8) & 0777;
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        for (int i = 0; i < ORDER.length; i++) {
            if ((bits & (1 << i)) != 0) {
                perms.add(ORDER[i]);
            }
        }
        return perms;
    }

    /** rwxr-xr-x → {@code 755}. */
    public static String mode(Set<PosixFilePermission> perms) {
        int bits = 0;
        for (int i = 0; i < ORDER.length; i++) {
            if (perms.contains(ORDER[i])) {
                bits |= 1 << i;
            }
        }
        return String.format("%03o", bits);
    }
}
