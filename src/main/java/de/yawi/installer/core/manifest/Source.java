package de.yawi.installer.core.manifest;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@code <source>}: one downloadable or bundled artifact and the providers
 * that can deliver it.
 *
 * @param sizeBytes       expected size, {@code -1} if not given
 * @param sha256          expected checksum as 64 hex characters, may be null
 * @param versionCheckUrl endpoint reporting the current version, may be null
 */
public record Source(String id, long sizeBytes, String sha256, List<Provider> providers, URI versionCheckUrl) {

    public Source {
        Objects.requireNonNull(id, "id");
        providers = List.copyOf(providers);
    }

    public Optional<String> sha256OrEmpty() {
        return Optional.ofNullable(sha256);
    }

    public Optional<URI> versionCheckUrlOrEmpty() {
        return Optional.ofNullable(versionCheckUrl);
    }

    public boolean hasProviderOfType(Class<? extends Provider> type) {
        return providers.stream().anyMatch(type::isInstance);
    }
}
