package de.yawi.installer.core.manifest;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One way of obtaining a {@link Source}'s data. Several providers on a source
 * form a fallback chain in manifest order.
 *
 * <p>Nested records on purpose: introduce top-level downloader classes
 * with similar names.
 */
public sealed interface Provider {

    /** Data shipped inside the installer, e.g. {@code classpath:/payload/x.zip}. */
    record Bundled(String path) implements Provider {
        public Bundled {
            Objects.requireNonNull(path, "path");
        }
    }

    /** Download over HTTP(S); additional URLs are mirrors tried in order. */
    record Http(List<URI> urls) implements Provider {
        public Http {
            urls = List.copyOf(urls);
            if (urls.isEmpty()) {
                throw new IllegalArgumentException("http provider needs at least one url");
            }
        }
    }

    /**
     * BitTorrent download, addressed either by magnet link or by a torrent
     * file reference; exactly one of the two is set.
     *
     * @param port        the listen port to try first; a free one is used if it is busy ({@code 0} = any free port)
     * @param peerTimeout how long the download may sit without a single peer
     *                    before the next provider is tried
     * @param fallback    whether that switch happens on its own or after asking the user
     * @param lsd         announce on the local network (local service discovery)
     * @param pex         exchange peers with connected peers (PEX)
     */
    record Torrent(String magnet, String torrentFile, int port, Duration peerTimeout, Fallback fallback,
                   boolean lsd, boolean pex) implements Provider {

        public static final int DEFAULT_PORT = 6881;
        public static final Duration DEFAULT_PEER_TIMEOUT = Duration.ofSeconds(90);

        /** What to do when no peer shows up within {@link #peerTimeout()}. */
        public enum Fallback { AUTO, ASK }

        public Torrent {
            if ((magnet == null) == (torrentFile == null)) {
                throw new IllegalArgumentException("torrent provider needs either magnet or torrentFile");
            }
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("torrent port out of range: " + port);
            }
            Objects.requireNonNull(peerTimeout, "peerTimeout");
            Objects.requireNonNull(fallback, "fallback");
        }

        /** With the defaults: port 6881, 90 s, automatic fallback, LSD and PEX on. */
        public Torrent(String magnet, String torrentFile) {
            this(magnet, torrentFile, DEFAULT_PORT, DEFAULT_PEER_TIMEOUT, Fallback.AUTO, true, true);
        }

        public Optional<String> magnetOrEmpty() {
            return Optional.ofNullable(magnet);
        }

        public Optional<String> torrentFileOrEmpty() {
            return Optional.ofNullable(torrentFile);
        }
    }
}
