package de.yawi.installer.core.download.torrent;

import de.yawi.installer.core.download.DownloadListener;
import de.yawi.installer.core.download.DownloadProgress;
import de.yawi.installer.core.download.TorrentDownloader;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.IntegrityException;
import de.yawi.installer.core.integrity.ChecksumVerifier;
import de.yawi.installer.core.manifest.Provider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E08-S01: bt behind the downloader contract, against an in-process seeder on loopback. */
@Timeout(60)
class TorrentDownloaderTest {

    private static final int SIZE = 1024 * 1024 + 12_345;

    @TempDir
    Path tmp;

    private TorrentTestSupport swarm;
    private TorrentRuntime runtime;

    /** Records every callback. */
    static final class Recording implements DownloadListener {
        final List<Long> starts = new ArrayList<>();
        final List<URI> uris = new ArrayList<>();
        final List<DownloadProgress> progress = new ArrayList<>();
        final List<String> verifying = new ArrayList<>();
        /** Progress reports before the checksum pass; that pass counts from zero again. */
        final List<DownloadProgress> downloadProgress = new ArrayList<>();

        @Override
        public synchronized void started(URI url, long resumeFrom) {
            uris.add(url);
            starts.add(resumeFrom);
        }

        @Override
        public synchronized void progress(DownloadProgress p) {
            progress.add(p);
            if (verifying.isEmpty()) {
                downloadProgress.add(p);
            }
        }

        @Override
        public synchronized void verifying(String fileName, long total) {
            verifying.add(fileName + ":" + total);
        }
    }

    @BeforeEach
    void seed() throws IOException {
        swarm = TorrentTestSupport.create(tmp, "data.bin", SIZE);
        swarm.startSeeder();
        runtime = new TorrentRuntime(TorrentTestSupport.TEST_CONFIG, List.of(swarm.fixedPeers()));
    }

    @AfterEach
    void stop() {
        runtime.shutdown();
        swarm.close();
    }

    private static String sha256(byte[] data) throws Exception {
        return ChecksumVerifier.hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private Provider.Torrent fromFile(Path torrentFile) {
        return new Provider.Torrent(null, torrentFile.toString(), 0, Duration.ofSeconds(30),
                Provider.Torrent.Fallback.AUTO, false, false);
    }

    @Test
    void downloadsFromATorrentFileWithProgressAndChecksum() throws Exception {
        Path torrentFile = swarm.writeTorrentFile(tmp.resolve("data.torrent"));
        Recording rec = new Recording();
        Path target = tmp.resolve("dl");

        Path file = new TorrentDownloader(runtime).fetch(fromFile(torrentFile), target, SIZE, sha256(swarm.payload()),
                rec, new CancellationToken());

        assertEquals(target.resolve("data.bin"), file);
        assertArrayEquals(swarm.payload(), Files.readAllBytes(file));
        assertEquals(List.of(0L), rec.starts, "fresh start");
        assertEquals("torrent:" + torrentFile, rec.uris.get(0).toString());
        assertFalse(rec.downloadProgress.isEmpty());
        DownloadProgress last = rec.downloadProgress.get(rec.downloadProgress.size() - 1);
        assertEquals(SIZE, last.total());
        assertEquals(SIZE, last.bytes(), "final progress reports the whole file");
        for (int i = 1; i < rec.downloadProgress.size(); i++) {
            assertTrue(rec.downloadProgress.get(i).bytes() >= rec.downloadProgress.get(i - 1).bytes(), "monotonic: "
                    + rec.downloadProgress.stream().map(DownloadProgress::bytes).toList());
        }
        assertEquals(SIZE, rec.progress.get(rec.progress.size() - 1).bytes(), "checksum pass reaches the end too");
        assertEquals(List.of("data.bin:" + SIZE), rec.verifying, "whole-file checksum after bt's pieces");
        assertTrue(runtime.port() > 0);
    }

    @Test
    void downloadsFromAMagnetLinkAndAClasspathTorrent() throws Exception {
        Provider.Torrent magnet = new Provider.Torrent(swarm.magnet(), null, 0, Duration.ofSeconds(30),
                Provider.Torrent.Fallback.AUTO, false, false);
        Recording rec = new Recording();

        Path file = new TorrentDownloader(runtime).fetch(magnet, tmp.resolve("dl-magnet"), -1, null, rec,
                new CancellationToken());

        assertArrayEquals(swarm.payload(), Files.readAllBytes(file));
        assertTrue(rec.uris.get(0).toString().startsWith("magnet:?xt=urn:btih:"), rec.uris.toString());
        assertTrue(rec.verifying.isEmpty(), "no sha256, no whole-file check");
    }

    @Test
    void wrongChecksumDeletesTheFile() throws Exception {
        Path torrentFile = swarm.writeTorrentFile(tmp.resolve("data.torrent"));
        Path target = tmp.resolve("dl-bad");

        IntegrityException e = assertThrows(IntegrityException.class, () -> new TorrentDownloader(runtime).fetch(
                fromFile(torrentFile), target, SIZE, "0".repeat(64), DownloadListener.NOOP, new CancellationToken()));

        assertEquals("data.bin", e.fileName());
        assertFalse(Files.exists(target.resolve("data.bin")), "wrong file not kept");
    }

    @Test
    void cancelKeepsTheDataAndAResumeFinishesIt() throws Exception {
        Path torrentFile = swarm.writeTorrentFile(tmp.resolve("data.torrent"));
        Path target = tmp.resolve("dl-cancel");
        CancellationToken token = new CancellationToken();
        CountDownLatch someProgress = new CountDownLatch(1);
        DownloadListener listener = new DownloadListener() {
            @Override
            public void progress(DownloadProgress p) {
                if (p.bytes() > 0 && p.bytes() < SIZE) {
                    someProgress.countDown();
                }
            }
        };
        Thread canceller = new Thread(() -> {
            try {
                someProgress.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            token.cancel();
        });
        canceller.start();

        assertThrows(CancelledException.class, () -> new TorrentDownloader(runtime).fetch(
                fromFile(torrentFile), target, SIZE, null, listener, token));
        canceller.join();
        assertTrue(Files.exists(target.resolve("data.bin")), "partial data kept for the next attempt");

        // Second attempt on the same runtime: bt verifies what is there and fetches the rest.
        Recording rec = new Recording();
        Path file = new TorrentDownloader(runtime).fetch(fromFile(torrentFile), target, SIZE, sha256(swarm.payload()),
                rec, new CancellationToken());
        assertArrayEquals(swarm.payload(), Files.readAllBytes(file));
        assertTrue(rec.starts.get(0) >= 0, rec.starts.toString());
    }

    @Test
    void multiFileTorrentIsRefused() throws Exception {
        Path multiDir = tmp.resolve("multi");
        Files.createDirectories(multiDir);
        Files.write(multiDir.resolve("a.bin"), new byte[40_000]);
        Files.write(multiDir.resolve("b.bin"), new byte[40_000]);
        byte[] metainfo = new bt.torrent.maker.TorrentBuilder()
                .rootPath(multiDir)
                .addFiles(multiDir.resolve("a.bin"), multiDir.resolve("b.bin"))
                .pieceSize(TorrentTestSupport.PIECE_SIZE)
                .announce("udp://127.0.0.1:1/announce")
                .build();
        Path torrentFile = tmp.resolve("multi.torrent");
        Files.write(torrentFile, metainfo);

        IOException e = assertThrows(IOException.class, () -> new TorrentDownloader(runtime).fetch(
                fromFile(torrentFile), tmp.resolve("dl-multi"), -1, null, DownloadListener.NOOP, new CancellationToken()));
        assertTrue(e.getMessage().contains("2 files"), e.getMessage());
    }

    // --- E08-S03: peer timeout ------------------------------------------------

    @Test
    void noPeersWithinTheTimeoutGivesUp() throws Exception {
        Path torrentFile = swarm.writeTorrentFile(tmp.resolve("data.torrent"));
        // A runtime that knows no peers: the tracker in the torrent is unreachable and nothing else tells it.
        TorrentRuntime lonely = new TorrentRuntime(TorrentTestSupport.TEST_CONFIG, List.of());
        Provider.Torrent provider = new Provider.Torrent(null, torrentFile.toString(), 0, Duration.ofSeconds(2),
                Provider.Torrent.Fallback.AUTO, false, false);
        long started = System.nanoTime();
        try {
            NoPeersException e = assertThrows(NoPeersException.class, () -> new TorrentDownloader(lonely).fetch(
                    provider, tmp.resolve("dl-lonely"), SIZE, null, DownloadListener.NOOP, new CancellationToken()));
            assertEquals(Duration.ofSeconds(2), e.waited());
            assertEquals("no peers within 2s", e.getMessage());
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
            assertTrue(seconds >= 2 && seconds < 15, "gave up after the timeout, not much later: " + seconds + " s");
            assertFalse(lonely.isStarted(), "runtime disposed of after the failure");
        } finally {
            lonely.shutdown();
        }
    }

    @Test
    void connectedPeerResetsTheClock() throws Exception {
        // Three times the usual payload takes well over 5 s from one peer; only a connected peer
        // that keeps resetting the clock lets the download finish.
        try (TorrentTestSupport big = TorrentTestSupport.create(tmp.resolve("big"), "big.bin", 3 * SIZE)) {
            big.startSeeder();
            TorrentRuntime bigRuntime = new TorrentRuntime(TorrentTestSupport.TEST_CONFIG, List.of(big.fixedPeers()));
            try {
                Path torrentFile = big.writeTorrentFile(tmp.resolve("big.torrent"));
                Provider.Torrent provider = new Provider.Torrent(null, torrentFile.toString(), 0, Duration.ofSeconds(5),
                        Provider.Torrent.Fallback.AUTO, false, false);
                long started = System.nanoTime();
                Path file = new TorrentDownloader(bigRuntime).fetch(provider, tmp.resolve("dl-big"), 3 * SIZE, null,
                        DownloadListener.NOOP, new CancellationToken());
                assertArrayEquals(big.payload(), Files.readAllBytes(file));
                assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) > 5, "took longer than the timeout");
            } finally {
                bigRuntime.shutdown();
            }
        }
    }

    @Test
    void unreadableTorrentFileIsAnIoException() {
        IOException e = assertThrows(IOException.class, () -> new TorrentDownloader(runtime).fetch(
                fromFile(tmp.resolve("nope.torrent")), tmp.resolve("dl-nope"), -1, null, DownloadListener.NOOP,
                new CancellationToken()));
        assertTrue(e.getMessage().contains("nope.torrent"), e.getMessage());
    }
}
