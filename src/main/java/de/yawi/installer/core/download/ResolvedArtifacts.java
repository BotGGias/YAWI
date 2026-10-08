package de.yawi.installer.core.download;

import de.yawi.installer.core.engine.Artifacts;
import de.yawi.installer.core.engine.BundledArtifacts;
import de.yawi.installer.core.manifest.Source;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * The {@link Artifacts} the engine runs with after the download phase:
 * resolved sources come from their file or bundled provider, anything else
 * falls back to {@link BundledArtifacts}.
 */
public final class ResolvedArtifacts implements Artifacts {

    private final Map<String, SourceResolver.Resolved> resolved;
    private final BundledArtifacts fallback = new BundledArtifacts();

    public ResolvedArtifacts(Map<String, SourceResolver.Resolved> resolved) {
        this.resolved = Map.copyOf(resolved);
    }

    @Override
    public InputStream open(Source source) throws IOException {
        SourceResolver.Resolved r = resolved.get(source.id());
        return r != null ? r.open() : fallback.open(source);
    }

    @Override
    public String describe(Source source) {
        SourceResolver.Resolved r = resolved.get(source.id());
        return r != null ? r.describe() : fallback.describe(source);
    }
}
