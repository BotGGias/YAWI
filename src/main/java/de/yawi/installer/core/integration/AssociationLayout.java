package de.yawi.installer.core.integration;

import de.yawi.installer.core.platform.Platform;

import java.nio.file.Path;

/**
 * Where the files of a Linux file association go: the shared-mime-info
 * package and the handler {@code .desktop}. The MIME tree sits next to the
 * shortcut's applications folder ({@code ~/.local/share/mime} or
 * {@code /usr/share/mime}), derived from the same XDG data directory.
 */
public final class AssociationLayout {

    private AssociationLayout() {
    }

    /** The MIME tree root {@code update-mime-database} is run on. */
    public static Path mimeDir(Platform platform, boolean systemWide) {
        return platform.applicationMenuDir(systemWide).resolveSibling("mime");
    }

    /** The shared-mime-info package file for this association. */
    public static Path mimePackage(Platform platform, boolean systemWide, AssociationSpec spec) {
        return mimeDir(platform, systemWide).resolve("packages").resolve(spec.baseName() + ".xml");
    }

    /** The handler {@code .desktop} that declares the MIME type and opens the target. */
    public static Path handlerDesktop(Platform platform, boolean systemWide, AssociationSpec spec) {
        return platform.applicationMenuDir(systemWide).resolve(handlerDesktopName(spec));
    }

    /** Just the file name of the handler {@code .desktop}, which {@code xdg-mime default} takes. */
    public static String handlerDesktopName(AssociationSpec spec) {
        return spec.baseName() + ".desktop";
    }
}
