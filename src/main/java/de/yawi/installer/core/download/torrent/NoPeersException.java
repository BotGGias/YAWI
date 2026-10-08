package de.yawi.installer.core.download.torrent;

import java.io.IOException;
import java.time.Duration;

/**
 * The torrent sat without a single connected peer for the provider's
 * {@code peerTimeout} (E08-S03): the network probably does not let
 * BitTorrent through, and the next provider should be tried.
 */
public class NoPeersException extends IOException {

    private final Duration waited;

    public NoPeersException(Duration waited) {
        super("no peers within " + de.yawi.installer.core.manifest.Durations.format(waited));
        this.waited = waited;
    }

    public Duration waited() {
        return waited;
    }
}
