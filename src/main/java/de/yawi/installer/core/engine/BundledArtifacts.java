package de.yawi.installer.core.engine;

import de.yawi.installer.core.error.SourceUnavailableException;
import de.yawi.installer.core.manifest.Provider;
import de.yawi.installer.core.manifest.ResourceRef;
import de.yawi.installer.core.manifest.Source;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Serves sources from their first {@code bundled} provider - the shipped
 * payload (classpath or file next to the application). Sources without one
 * are unavailable here;
 */
public final class BundledArtifacts implements Artifacts {

    @Override
    public InputStream open(Source source) throws IOException {
        String path = bundledPath(source).orElseThrow(() -> new SourceUnavailableException(
                source.id(), "no bundled provider and downloads are not available yet (E07)", null));
        return ResourceRef.open(path);
    }

    @Override
    public String describe(Source source) {
        return bundledPath(source).orElse(source.id());
    }

    private static Optional<String> bundledPath(Source source) {
        return source.providers().stream()
                .filter(Provider.Bundled.class::isInstance)
                .map(p -> ((Provider.Bundled) p).path())
                .findFirst();
    }
}
