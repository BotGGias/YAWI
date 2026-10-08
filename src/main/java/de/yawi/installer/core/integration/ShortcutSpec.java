package de.yawi.installer.core.integration;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A shortcut with every placeholder resolved: what to launch, from where,
 * under which name, with which icon.
 *
 * @param productId  the manifest's product id; part of the file names on Linux
 * @param shortcutId the {@code <shortcut id>}
 * @param name       the visible name
 * @param target     the executable (or file inside an {@code .app} bundle) to launch
 * @param workingDir the launched program's working directory, normally the destination
 * @param iconFile   an icon file on disk, if the manifest named one that exists
 * @param desktop    {@code @desktop}: a shortcut on the desktop
 * @param menu       {@code @menu}: an entry in the application menu / Start Menu / Applications folder
 */
public record ShortcutSpec(String productId, String shortcutId, String name, Path target, Path workingDir,
                           Optional<Path> iconFile, boolean desktop, boolean menu) {

    public ShortcutSpec {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(shortcutId, "shortcutId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(iconFile, "iconFile");
    }
}
