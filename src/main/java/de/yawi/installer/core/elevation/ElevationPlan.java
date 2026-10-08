package de.yawi.installer.core.elevation;

import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.manifest.ManifestOrigin;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything the elevated process needs to run its part of a plan
 * - and nothing else: it downloads nothing, registers nothing, and accepts no
 * further instruction than the {@code go} and {@code cancel} markers.
 *
 * @param destination      the installation folder
 * @param startedAt        start of the whole run (backup folder, record header)
 * @param selected         the resolved component selection
 * @param inputs           the selected components' input values, secrets included
 * @param stepIds          the steps to run, in plan order (all of them for {@link ElevationNeed.Mode#WHOLE})
 * @param artifacts        where each needed source's data is, keyed by source id
 * @param origin           the manifest's origin, for the trust decision of {@code ExecutionPlan.build}
 * @param env              the calling user's environment variables the platform reads
 * @param properties       the calling user's system properties the platform reads
 * @param ownerUser        the calling user's account name for the ownership pass, empty on Windows
 * @param ownerGroup       the calling user's primary group, empty on Windows
 * @param chownDestination whether files the elevated process creates under the destination belong
 *                         to the calling user afterwards (a user-writable destination, {@code PARTIAL})
 */
public record ElevationPlan(ElevationNeed.Mode mode, Path destination, Instant startedAt, Set<String> selected,
                            Map<String, String> inputs, List<String> stepIds, Map<String, Artifact> artifacts,
                            ManifestOrigin origin, Map<String, String> env, Map<String, String> properties,
                            Optional<String> ownerUser, Optional<String> ownerGroup, boolean chownDestination) {

    public static final int FORMAT_VERSION = 1;

    /**
     * One source's data: a file (downloaded, cached, or a bundled file next
     * to the installer, absolute) or a classpath resource of the installer.
     *
     * @param kind the provider that delivered, so the child picks the same one from the manifest
     */
    public record Artifact(ProviderKind kind, Optional<Path> file, Optional<String> classpathRef) {
        public Artifact {
            if (file.isEmpty() == classpathRef.isEmpty()) {
                throw new IllegalArgumentException("exactly one of file and classpathRef");
            }
        }

        public static Artifact file(ProviderKind kind, Path file) {
            return new Artifact(kind, Optional.of(file.toAbsolutePath().normalize()), Optional.empty());
        }

        public static Artifact classpath(String ref) {
            return new Artifact(ProviderKind.BUNDLED, Optional.empty(), Optional.of(ref));
        }
    }

    public ElevationPlan {
        destination = destination.toAbsolutePath().normalize();
        selected = Set.copyOf(selected);
        inputs = Map.copyOf(inputs);
        stepIds = List.copyOf(stepIds);
        artifacts = Map.copyOf(artifacts);
        env = Map.copyOf(env);
        properties = Map.copyOf(properties);
    }
}
