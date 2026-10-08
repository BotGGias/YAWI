package de.yawi.installer.core.engine;

import de.yawi.installer.core.integrity.PathEscapeException;
import de.yawi.installer.core.integrity.PathGuard;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What "launch the application" means: the target of the manifest's
 * first {@code <shortcut>} with placeholders resolved, confined to the
 * destination. Shared by the finish page and {@code --relaunch}.
 */
public final class LaunchTarget {

    private static final Logger LOG = LoggerFactory.getLogger(LaunchTarget.class);

    private LaunchTarget() {
    }

    /** Empty if the manifest declares no shortcut or its target escapes the destination. */
    public static Optional<Path> of(InstallManifest manifest, Platform platform, Path destination) {
        if (manifest.integration().shortcuts().isEmpty()) {
            return Optional.empty();
        }
        String target = manifest.integration().shortcuts().get(0).target();
        String resolved = Placeholders.of(manifest, platform, destination, Map.of(), List.of()).resolve(target);
        try {
            return Optional.of(new PathGuard(destination).confine(Path.of(resolved)));
        } catch (PathEscapeException e) {
            LOG.warn("Launch target {} ignored: {}", resolved, e.getMessage());
            return Optional.empty();
        }
    }
}
