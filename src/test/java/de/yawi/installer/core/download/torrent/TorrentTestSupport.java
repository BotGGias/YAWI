package de.yawi.installer.core.download.torrent;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.metainfo.MetadataService;
import bt.metainfo.Torrent;
import bt.metainfo.TorrentId;
import bt.module.ServiceModule;
import bt.net.InetPeer;
import bt.net.Peer;
import bt.peer.PeerSource;
import bt.peer.PeerSourceFactory;
import bt.protocol.crypto.EncryptionPolicy;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import bt.torrent.maker.TorrentBuilder;
import com.google.inject.Binder;
import com.google.inject.Module;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * A torrent on loopback for the E08 tests: a payload, its {@code .torrent},
 * and an in-process seeder on its own bt runtime. Peer discovery does not
 * use the network - the leecher gets the seeder's address through
 * {@link FixedPeersModule} instead of a tracker.
 */
public final class TorrentTestSupport implements AutoCloseable {

    public static final int PIECE_SIZE = 16 * 1024;

    /** Loopback, plaintext, small blocks - what every runtime in the tests shares. */
    public static final Consumer<Config> TEST_CONFIG = config -> {
        config.setAcceptorAddress(InetAddress.getLoopbackAddress());
        config.setEncryptionPolicy(EncryptionPolicy.REQUIRE_PLAINTEXT);
        config.setPeerConnectionRetryInterval(Duration.ofMillis(200));
        config.setPeerDiscoveryInterval(Duration.ofMillis(500));
        config.setShutdownHookTimeout(Duration.ofSeconds(5));
    };

    private final Path seedDir;
    private final byte[] payload;
    private final byte[] metainfo;
    private final Torrent torrent;
    private BtRuntime seederRuntime;
    private BtClient seeder;
    private int seederPort = -1;

    private TorrentTestSupport(Path seedDir, byte[] payload, byte[] metainfo, Torrent torrent) {
        this.seedDir = seedDir;
        this.payload = payload;
        this.metainfo = metainfo;
        this.torrent = torrent;
    }

    /** A single-file torrent of {@code size} random bytes named {@code fileName}, written under {@code dir}. */
    public static TorrentTestSupport create(Path dir, String fileName, int size) throws IOException {
        byte[] payload = new byte[size];
        new Random(size).nextBytes(payload);
        return create(dir, fileName, payload);
    }

    /** A single-file torrent of the given bytes. */
    public static TorrentTestSupport create(Path dir, String fileName, byte[] payload) throws IOException {
        Path seedDir = dir.resolve("seed");
        Files.createDirectories(seedDir);
        Path file = seedDir.resolve(fileName);
        Files.write(file, payload);
        byte[] metainfo = new TorrentBuilder()
                .rootPath(seedDir)
                .addFile(file)
                .pieceSize(PIECE_SIZE)
                // unreachable on purpose: peers come from FixedPeersModule
                .announce("udp://127.0.0.1:1/announce")
                .createdBy("yawi-installer test")
                .build();
        Torrent torrent = new MetadataService().fromInputStream(new ByteArrayInputStream(metainfo));
        return new TorrentTestSupport(seedDir, payload, metainfo, torrent);
    }

    public byte[] payload() {
        return payload;
    }

    public byte[] metainfo() {
        return metainfo;
    }

    public Torrent torrent() {
        return torrent;
    }

    public Path writeTorrentFile(Path file) throws IOException {
        Files.write(file, metainfo);
        return file;
    }

    public String magnet() {
        return "magnet:?xt=urn:btih:" + torrent.getTorrentId().toString() + "&dn=" + torrent.getName();
    }

    /** Starts seeding from the complete file; the runtime picks a free loopback port. */
    public synchronized void startSeeder() {
        if (seeder != null) {
            return;
        }
        Config config = new Config();
        TEST_CONFIG.accept(config);
        config.setAcceptorPort(TorrentRuntime.choosePort(0));
        seederRuntime = BtRuntime.builder(config)
                .disableAutomaticShutdown()
                .disableLocalServiceDiscovery()
                .build();
        seederPort = config.getAcceptorPort();
        seeder = Bt.client(seederRuntime)
                .storage(new FileSystemStorage(seedDir))
                .torrent(() -> torrent)
                .build();
        seeder.startAsync();
    }

    public int seederPort() {
        if (seederPort < 0) {
            throw new IllegalStateException("seeder not started");
        }
        return seederPort;
    }

    /** A module that tells bt about the seeder as the only peer of every torrent. */
    public Module fixedPeers() {
        return new FixedPeersModule(seederPort());
    }

    @Override
    public synchronized void close() {
        if (seeder != null) {
            seeder.stop();
            seeder = null;
        }
        if (seederRuntime != null) {
            seederRuntime.shutdown();
            seederRuntime = null;
        }
    }

    /** Contributes one {@link PeerSource} with a fixed loopback address - a tracker without the tracker. */
    public static final class FixedPeersModule implements Module {
        private final int port;

        public FixedPeersModule(int port) {
            this.port = port;
        }

        @Override
        public void configure(Binder binder) {
            ServiceModule.contributePeerSourceFactory(binder).addBinding().toInstance(new PeerSourceFactory() {
                @Override
                public PeerSource getPeerSource(TorrentId torrentId) {
                    Peer peer = InetPeer.build(InetAddress.getLoopbackAddress(), port);
                    return new PeerSource() {
                        @Override
                        public boolean update() {
                            return true;
                        }

                        @Override
                        public Collection<Peer> getPeers() {
                            return new java.util.ArrayList<>(List.of(peer)); // bt removes itself from the list
                        }
                    };
                }
            });
        }
    }
}
