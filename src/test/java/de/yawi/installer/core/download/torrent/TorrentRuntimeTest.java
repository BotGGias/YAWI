package de.yawi.installer.core.download.torrent;

import bt.runtime.BtRuntime;
import de.yawi.installer.core.download.DownloadListener;
import de.yawi.installer.core.download.DownloadProgress;
import de.yawi.installer.core.download.TorrentDownloader;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.manifest.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E08-S02: one runtime per process, a busy port, and a clean stop that leaves no thread and no port behind. */
@Timeout(90)
class TorrentRuntimeTest {

    private static final int SIZE = 1024 * 1024 + 999;

    @TempDir
    Path tmp;

    private static Provider.Torrent provider(Path torrentFile, int port) {
        return new Provider.Torrent(null, torrentFile.toString(), port, Duration.ofSeconds(30),
                Provider.Torrent.Fallback.AUTO, false, false);
    }

    @Test
    void busyPortIsReplacedByAFreeOne() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int wanted = taken.getLocalPort();
            int chosen = TorrentRuntime.choosePort(wanted);
            assertNotEquals(wanted, chosen);
            assertTrue(chosen > 0);
        }
        try (ServerSocket probe = new ServerSocket(0)) {
            int free = probe.getLocalPort();
            probe.close();
            assertEquals(free, TorrentRuntime.choosePort(free), "a free port is kept");
        }
        assertTrue(TorrentRuntime.choosePort(0) > 0, "0 = any free port");
    }

    @Test
    void oneRuntimeIsSharedAndShutdownLeavesNothingBehind() throws Exception {
        try (TorrentTestSupport swarm = TorrentTestSupport.create(tmp, "data.bin", SIZE)) {
            swarm.startSeeder();
            TorrentRuntime runtime = new TorrentRuntime(TorrentTestSupport.TEST_CONFIG, List.of(swarm.fixedPeers()));
            Path torrentFile = swarm.writeTorrentFile(tmp.resolve("data.torrent"));
            Path target = tmp.resolve("dl");
            TorrentDownloader downloader = new TorrentDownloader(runtime);

            // First attempt: cancelled after the first bytes.
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
            assertThrows(CancelledException.class, () -> downloader.fetch(provider(torrentFile, 0), target, SIZE, null,
                    listener, token));
            canceller.join();
            assertFalse(runtime.isStarted(), "every download disposes of its runtime");

            // Second attempt: a fresh runtime on the same port, resumes what is on disk.
            List<Long> starts = new java.util.ArrayList<>();
            Path file = downloader.fetch(provider(torrentFile, 0), target, SIZE, null, new DownloadListener() {
                @Override
                public void started(java.net.URI url, long resumeFrom) {
                    starts.add(resumeFrom);
                }
            }, new CancellationToken());
            assertArrayEquals(swarm.payload(), Files.readAllBytes(file));
            assertEquals(1, starts.size());
            assertTrue(starts.get(0) > 0, "resumed from what the first attempt left: " + starts);
            assertFalse(runtime.isStarted());
            int port = runtime.port();

            // Outside a download the runtime stays up until someone shuts it down - once, or twice.
            BtRuntime rt = runtime.runtime(provider(torrentFile, port));
            rt.startup();
            assertSame(rt, runtime.runtime(provider(torrentFile, port)), "one runtime at a time");
            assertTrue(runtime.isStarted());
            assertEquals(port, runtime.port(), "the same port again");
            runtime.shutdown();
            runtime.shutdown();
            assertFalse(runtime.isStarted());

            // No bt thread of this port left, the port is bindable again.
            String prefix = port + ".bt.";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Set<String> left;
            do {
                left = Thread.getAllStackTraces().keySet().stream()
                        .map(Thread::getName)
                        .filter(n -> n.startsWith(prefix))
                        .collect(Collectors.toSet());
                if (left.isEmpty()) {
                    break;
                }
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            assertTrue(left.isEmpty(), "bt threads still alive: " + left);
            try (ServerSocket rebound = new ServerSocket(port)) {
                assertEquals(port, rebound.getLocalPort(), "port released");
            }
        }
    }
}
