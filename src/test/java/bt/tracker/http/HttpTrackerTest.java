package bt.tracker.http;

import bt.metainfo.Torrent;
import bt.metainfo.TorrentId;
import bt.net.InetPeer;
import bt.net.Peer;
import bt.net.PeerId;
import bt.peer.IPeerRegistry;
import bt.protocol.crypto.EncryptionPolicy;
import bt.service.IdentityService;
import bt.torrent.TorrentDescriptor;
import bt.torrent.TorrentRegistry;
import bt.torrent.TorrentSessionState;
import bt.tracker.AnnounceKey;
import bt.tracker.SecretKey;
import bt.tracker.TrackerResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the vendored HTTP tracker client on the JDK's HttpClient - the
 * announce query and the bencoded answer are what upstream sends and expects.
 */
class HttpTrackerTest {

    private static final byte[] INFO_HASH = new byte[20];
    private static final byte[] PEER_ID = new byte[20];

    static {
        for (int i = 0; i < 20; i++) {
            INFO_HASH[i] = (byte) i;
            PEER_ID[i] = (byte) ('A' + i);
        }
    }

    private HttpServer server;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private volatile byte[] answer = compactAnswer();
    private volatile int status = 200;

    /** {@code d8:intervali1800e5:peers6:<127.0.0.1:6666>e} - one peer in compact form. */
    private static byte[] compactAnswer() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("d8:intervali1800e5:peers6:".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[] {127, 0, 0, 1, (byte) (6666 >> 8), (byte) (6666 & 0xff)});
        out.writeBytes("e".getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/announce", exchange -> {
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(status, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpTracker tracker() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/announce";
        return new HttpTracker(url, new NoTorrents(), new FixedIdentity(), new LocalPeerOnly(),
                EncryptionPolicy.PREFER_PLAINTEXT, InetAddress.getLoopbackAddress(), 50, Duration.ofSeconds(5));
    }

    @Test
    void announcesAndParsesCompactPeers() {
        TrackerResponse response = tracker().request(TorrentId.fromBytes(INFO_HASH)).start();

        assertTrue(response.isSuccess(), String.valueOf(response.getError()));
        assertEquals(1800, response.getInterval());
        List<Peer> peers = new ArrayList<>();
        response.getPeers().forEach(peers::add);
        assertEquals(1, peers.size());
        assertEquals("127.0.0.1", peers.get(0).getInetAddress().getHostAddress());
        assertEquals(6666, peers.get(0).getPort());

        String query = lastQuery.get();
        assertTrue(query.contains("info_hash=%00%01%02%03%04%05%06%07%08%09%0A%0B%0C%0D%0E%0F%10%11%12%13"), query);
        // Upstream's encoder escapes digits too (%36%38%38%31); trackers decode either way.
        String decoded = java.net.URLDecoder.decode(query.replaceFirst("info_hash=[^&]*&", ""), StandardCharsets.ISO_8859_1);
        assertTrue(decoded.contains("peer_id=ABCDEFGHIJKLMNOPQRST"), decoded);
        assertTrue(decoded.contains("&port=6881&"), decoded);
        assertTrue(decoded.contains("&event=started"), decoded);
        assertTrue(decoded.contains("&compact=1&"), decoded);
        assertTrue(decoded.contains("&numwant=50&"), decoded);
        assertTrue(decoded.contains("&supportcrypto=1"), decoded);
    }

    @Test
    void failureReasonAndHttpErrorsAreReportedNotThrown() {
        answer = "d14:failure reason9:not todaye".getBytes(StandardCharsets.ISO_8859_1);
        TrackerResponse failure = tracker().request(TorrentId.fromBytes(INFO_HASH)).query();
        assertFalse(failure.isSuccess());
        assertEquals("not today", failure.getErrorMessage());

        status = 503;
        TrackerResponse error = tracker().request(TorrentId.fromBytes(INFO_HASH)).query();
        assertFalse(error.isSuccess());
        assertTrue(error.getError().isPresent());
        assertTrue(error.getError().get().getMessage().contains("503"), error.getError().get().getMessage());

        server.stop(0);
        TrackerResponse unreachable = tracker().request(TorrentId.fromBytes(INFO_HASH)).query();
        assertFalse(unreachable.isSuccess());
        assertTrue(unreachable.getError().isPresent());
    }

    // --- minimal collaborators -------------------------------------------

    private static final class FixedIdentity implements IdentityService {
        @Override
        public PeerId getLocalPeerId() {
            return PeerId.fromBytes(PEER_ID);
        }

        @Override
        public Optional<SecretKey> getSecretKey() {
            return Optional.empty();
        }
    }

    private static final class LocalPeerOnly implements IPeerRegistry {
        @Override
        public Peer getLocalPeer() {
            return InetPeer.builder(InetAddress.getLoopbackAddress(), 6881).build();
        }

        @Override
        public void addPeer(TorrentId torrentId, Peer peer) {
        }

        @Override
        public void addPeerSource(TorrentId torrentId, AnnounceKey announceKey) {
        }

        @Override
        public void triggerPeerCollection(TorrentId torrentId) {
        }
    }

    private static final class NoTorrents implements TorrentRegistry {
        @Override
        public Collection<Torrent> getTorrents() {
            return List.of();
        }

        @Override
        public Collection<TorrentId> getTorrentIds() {
            return List.of();
        }

        @Override
        public Optional<Torrent> getTorrent(TorrentId torrentId) {
            return Optional.empty();
        }

        @Override
        public Optional<TorrentDescriptor> getDescriptor(Torrent torrent) {
            return Optional.empty();
        }

        @Override
        public Optional<TorrentDescriptor> getDescriptor(TorrentId torrentId) {
            return Optional.empty();
        }

        @Override
        public TorrentDescriptor getOrCreateDescriptor(Torrent torrent, bt.data.Storage storage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerSessionState(TorrentId torrentId, TorrentSessionState state) {
        }

        @Override
        public TorrentDescriptor register(TorrentId torrentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TorrentDescriptor register(Torrent torrent, bt.data.Storage storage,
                                          bt.torrent.callbacks.FileDownloadCompleteCallback callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSupportedAndActive(TorrentId torrentId) {
            return false;
        }
    }
}
