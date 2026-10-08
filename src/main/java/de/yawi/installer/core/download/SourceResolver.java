package de.yawi.installer.core.download;

import de.yawi.installer.core.download.torrent.FallbackPrompt;
import de.yawi.installer.core.download.torrent.NoPeersException;
import de.yawi.installer.core.download.torrent.TorrentRuntime;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.IntegrityException;
import de.yawi.installer.core.error.SourceUnavailableException;
import de.yawi.installer.core.integrity.ChecksumVerifier;
import de.yawi.installer.core.manifest.Provider;
import de.yawi.installer.core.manifest.ResourceRef;
import de.yawi.installer.core.manifest.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Walks a source's providers - the user's preferred kind first, then manifest
 * order - until one delivers. Bundled data is not copied; the
 * result says "stream it from the bundled provider". Every attempt is logged;
 * if all fail the exception lists them.
 *
 * <p>With a {@code sha256} on the source every provider's data is checked
 * downloads while they are written ({@link Downloader}), bundled
 * data in one read before anything streams it. A mismatch counts as a failed
 * provider; if in the end nothing delivered and at least one provider failed
 * the check, the failure is reported as an integrity problem rather than an
 * unreachable source.
 */
public final class SourceResolver {

    private static final Logger LOG = LoggerFactory.getLogger(SourceResolver.class);

    /**
     * Where a source's data is now.
     *
     * @param file     the downloaded file, or empty for bundled data (read through {@code ResourceRef})
     * @param provider the provider that delivered
     */
    public record Resolved(Source source, Optional<Path> file, Provider provider) {
        public ProviderKind kind() {
            return ProviderKind.of(provider);
        }

        public InputStream open() throws IOException {
            if (file.isPresent()) {
                return java.nio.file.Files.newInputStream(file.get());
            }
            return ResourceRef.open(((Provider.Bundled) provider).path());
        }

        /** A name the extract step can guess the archive format from. */
        public String describe() {
            return file.map(f -> f.getFileName().toString())
                    .orElseGet(() -> ((Provider.Bundled) provider).path());
        }
    }

    private final Downloader downloader;
    /** Null = torrent providers are skipped (tests without a bt runtime). */
    private final TorrentDownloader torrents;
    private final FallbackPrompt fallbackPrompt;

    /** The kind to try first when the user made no choice: bundled if shipped, else download, else torrent. */
    public static ProviderKind defaultKind(Source source) {
        return source.providers().stream()
                .map(ProviderKind::of)
                .min(java.util.Comparator.naturalOrder())
                .orElse(ProviderKind.BUNDLED);
    }

    /** HTTP only; torrent providers are skipped with a log line. */
    public SourceResolver(Downloader downloader) {
        this(downloader, null);
    }

    /** @param torrents the BitTorrent downloader (E08), or {@code null} to skip torrent providers */
    public SourceResolver(Downloader downloader, TorrentDownloader torrents) {
        this(downloader, torrents, FallbackPrompt.ALWAYS);
    }

    public SourceResolver(Downloader downloader, TorrentDownloader torrents, FallbackPrompt fallbackPrompt) {
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.torrents = torrents;
        this.fallbackPrompt = Objects.requireNonNull(fallbackPrompt, "fallbackPrompt");
    }

    /** The same downloaders, asking {@code prompt} before leaving a torrent with {@code fallback="ask"}. */
    public SourceResolver withFallbackPrompt(FallbackPrompt prompt) {
        return new SourceResolver(downloader, torrents, prompt);
    }

    /** The resolver of the running installer: HTTP plus BitTorrent on the shared runtime. */
    public static SourceResolver forRuntime() {
        return new SourceResolver(new HttpDownloader(), new TorrentDownloader(TorrentRuntime.shared()));
    }

    /**
     * @param preferred   the kind to try first; providers of other kinds follow in manifest order
     * @param downloadDir where downloads go ({@code <dir>/<sourceId>/<file name>})
     * @throws SourceUnavailableException if no provider delivered
     */
    public Resolved resolve(Source source, ProviderKind preferred, Path downloadDir, DownloadListener listener,
                            CancellationToken cancellation) {
        List<Provider> order = new ArrayList<>();
        source.providers().stream().filter(p -> ProviderKind.of(p) == preferred).forEach(order::add);
        source.providers().stream().filter(p -> ProviderKind.of(p) != preferred).forEach(order::add);

        if (source.sha256() == null && source.providers().stream().anyMatch(p -> !(p instanceof Provider.Bundled))) {
            LOG.warn("Source {} has no sha256; downloaded data cannot be verified", source.id());
        }

        List<String> attempts = new ArrayList<>();
        boolean integrityFailure = false;
        for (int i = 0; i < order.size(); i++) {
            Provider provider = order.get(i);
            Provider next = i + 1 < order.size() ? order.get(i + 1) : null;
            cancellation.checkpoint();
            ProviderKind kind = ProviderKind.of(provider);
            try {
                switch (provider) {
                    case Provider.Bundled b -> {
                        try (InputStream in = ResourceRef.open(b.path())) {
                            Objects.requireNonNull(in);
                            if (source.sha256() != null) {
                                verifyBundled(source, b, in, listener, cancellation);
                            }
                        }
                        LOG.info("Source {}: using bundled {}", source.id(), b.path());
                        return new Resolved(source, Optional.empty(), provider);
                    }
                    case Provider.Http h -> {
                        Path target = downloadDir.resolve(source.id()).resolve(fileName(h.urls().get(0), source.id()));
                        LOG.info("Source {}: downloading to {}", source.id(), target);
                        Path file = downloader.fetch(h.urls(), target, source.sizeBytes(), source.sha256(), listener,
                                cancellation);
                        return new Resolved(source, Optional.of(file), provider);
                    }
                    case Provider.Torrent t -> {
                        if (torrents == null) {
                            LOG.info("Source {}: torrent provider skipped, no BitTorrent runtime here", source.id());
                            attempts.add("torrent: not available");
                            continue;
                        }
                        Path dir = downloadDir.resolve(source.id());
                        LOG.info("Source {}: torrent download to {}", source.id(), dir);
                        Path file = torrents.fetch(t, dir, source.sizeBytes(), source.sha256(), listener, cancellation);
                        return new Resolved(source, Optional.of(file), provider);
                    }
                }
            } catch (CancelledException e) {
                throw e;
            } catch (IntegrityException e) {
                LOG.warn("Source {}: {} provider failed the checksum: {}", source.id(), kind, e.getMessage());
                attempts.add(kind.name().toLowerCase(java.util.Locale.ROOT) + ": checksum mismatch");
                integrityFailure = true;
                announceFallback(kind, next, "checksum mismatch", listener);
            } catch (NoPeersException e) {
                LOG.warn("Source {}: torrent provider found no peers: {}", source.id(), e.getMessage());
                attempts.add("torrent: " + e.getMessage());
                if (next != null && ((Provider.Torrent) provider).fallback() == Provider.Torrent.Fallback.ASK
                        && fallbackPrompt.ask(source, (Provider.Torrent) provider, e) == FallbackPrompt.Decision.ABORT) {
                    throw new CancelledException("user declined to continue without BitTorrent", e);
                }
                announceFallback(kind, next, e.getMessage(), listener);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Source {}: {} provider failed: {}", source.id(), kind, e.toString());
                attempts.add(kind.name().toLowerCase(java.util.Locale.ROOT) + ": " + e.getMessage());
                announceFallback(kind, next, e.getMessage(), listener);
            }
        }
        if (integrityFailure) {
            throw new IntegrityException(source.id(), String.join("; ", attempts), String.join("\n", attempts));
        }
        throw new SourceUnavailableException(source.id(), String.join("; ", attempts), null, String.join("\n", attempts));
    }

    private static void announceFallback(ProviderKind from, Provider next, String reason, DownloadListener listener) {
        if (next != null) {
            listener.fallback(from, ProviderKind.of(next), reason);
        }
    }

    /** One pass over the bundled data before anything streams it; progress goes to the listener. */
    private static void verifyBundled(Source source, Provider.Bundled b, InputStream in, DownloadListener listener,
                                      CancellationToken cancellation) throws IOException {
        long total = source.sizeBytes() > 0 ? source.sizeBytes() : -1;
        LOG.info("Source {}: verifying bundled {} ({} bytes expected)", source.id(), b.path(), total);
        listener.verifying(b.path(), total);
        long[] read = {0, 0}; // bytes so far, bytes at the last report
        ChecksumVerifier.verifyStream(in, source.sha256(), b.path(), bytes -> {
            cancellation.checkpoint();
            read[0] = bytes;
            if (bytes - read[1] >= 1L << 20) {
                read[1] = bytes;
                listener.progress(new DownloadProgress(bytes, total, 0, -1));
            }
        });
        listener.progress(new DownloadProgress(read[0], total < 0 ? read[0] : total, 0, -1));
    }

    /** The last path segment of the URL, or the source id if the URL has none. */
    static String fileName(URI url, String fallback) {
        String path = url.getPath();
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            return fallback;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? fallback : name;
    }
}
