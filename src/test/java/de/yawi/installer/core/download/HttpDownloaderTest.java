package de.yawi.installer.core.download;

import de.yawi.installer.core.download.HttpDownloader.Backoff;
import de.yawi.installer.core.download.HttpDownloader.Timeouts;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.IntegrityException;
import de.yawi.installer.core.integrity.ChecksumVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E07-S02: resume, mirrors, retries, redirects, stall, cancel and progress against a local server. */
@Timeout(60)
class HttpDownloaderTest {

    private static TestHttpServer server;

    @TempDir
    Path tmp;

    @BeforeAll
    static void start() throws IOException {
        server = new TestHttpServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    /** Records every callback. */
    static final class Recording implements DownloadListener {
        final List<Long> starts = new ArrayList<>();
        final List<DownloadProgress> progress = new ArrayList<>();
        final List<String> retries = new ArrayList<>();

        @Override
        public synchronized void started(URI url, long resumeFrom) {
            starts.add(resumeFrom);
        }

        @Override
        public synchronized void progress(DownloadProgress p) {
            progress.add(p);
        }

        @Override
        public synchronized void retry(URI url, int attempt, Throwable cause) {
            retries.add(url.getPath() + "#" + attempt + ": " + cause.getMessage());
        }
    }

    private static HttpDownloader downloader() {
        return new HttpDownloader(new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(60)),
                Backoff.NONE, 3);
    }

    private Path target() {
        return tmp.resolve("dl/file.bin");
    }

    private static String bodySha256() throws Exception {
        return ChecksumVerifier.hex(java.security.MessageDigest.getInstance("SHA-256").digest(TestHttpServer.BODY));
    }

    // --- E10-S01: checksum while writing ------------------------------------

    @Test
    void matchingChecksumKeepsTheFile() throws Exception {
        Path file = downloader().fetch(List.of(server.uri("/ok")), target(), -1, bodySha256(), DownloadListener.NOOP,
                new CancellationToken());
        assertArrayEquals(TestHttpServer.BODY, Files.readAllBytes(file));
    }

    @Test
    void wrongChecksumDeletesTheFileAndIsNotRetried() throws Exception {
        Recording rec = new Recording();
        String wrong = "0".repeat(64);
        IntegrityException e = assertThrows(IntegrityException.class, () -> downloader().fetch(
                List.of(server.uri("/ok"), server.uri("/ok")), target(), -1, wrong, rec, new CancellationToken()));
        assertEquals("file.bin", e.fileName());
        assertTrue(e.getMessage().contains("expected " + wrong), e.getMessage());
        assertFalse(Files.exists(target()), "wrong file not kept");
        assertFalse(Files.exists(HttpDownloader.partFile(target())), "part not kept either");
        assertEquals(List.of(0L), rec.starts, "the complete but wrong file is not downloaded again");
        assertTrue(rec.retries.isEmpty());
    }

    @Test
    void checksumCoversTheResumedPartToo() throws Exception {
        server.dropRequests.set(0);
        Recording rec = new Recording();
        Path file = downloader().fetch(List.of(server.uri("/drop")), target(), -1, bodySha256(), rec,
                new CancellationToken());
        assertArrayEquals(TestHttpServer.BODY, Files.readAllBytes(file));
        assertEquals(2, rec.starts.size(), "resumed once");
        assertEquals(300L * 1024, rec.starts.get(1));
    }

    @Test
    void downloadsTheWholeFileWithProgress() throws Exception {
        Recording rec = new Recording();
        Path file = downloader().fetch(List.of(server.uri("/ok")), target(), -1, null, rec, new CancellationToken());

        assertEquals(target(), file);
        assertArrayEquals(TestHttpServer.BODY, Files.readAllBytes(file));
        assertFalse(Files.exists(HttpDownloader.partFile(target())), "part renamed");
        assertEquals(List.of(0L), rec.starts);
        DownloadProgress last = rec.progress.get(rec.progress.size() - 1);
        assertEquals(TestHttpServer.BODY_SIZE, last.bytes());
        assertEquals(TestHttpServer.BODY_SIZE, last.total(), "Content-Length known");
        assertEquals(1.0, last.fraction(), 1e-9);
        for (int i = 1; i < rec.progress.size(); i++) {
            assertTrue(rec.progress.get(i).bytes() >= rec.progress.get(i - 1).bytes(), "monotonic");
        }
    }

    @Test
    void resumesAfterTheConnectionDropped() throws Exception {
        server.dropRequests.set(0);
        Recording rec = new Recording();
        Path file = downloader().fetch(List.of(server.uri("/drop")), target(), -1, null, rec, new CancellationToken());

        assertArrayEquals(TestHttpServer.BODY, Files.readAllBytes(file));
        assertEquals(2, rec.starts.size(), rec.starts.toString());
        assertEquals(0L, rec.starts.get(0));
        assertEquals(300L * 1024, rec.starts.get(1), "second attempt resumed where the first stopped (206)");
        assertEquals(1, rec.retries.size(), rec.retries.toString());
        // Either the client notices the cut ("closed") or we do after a clean EOF.
        assertTrue(rec.retries.get(0).contains("closed"), rec.retries.get(0));
    }

    @Test
    void serverWithoutRangesRestartsFromZero() throws Exception {
        Path part = HttpDownloader.partFile(target());
        Files.createDirectories(part.getParent());
        Files.write(part, Arrays.copyOf(TestHttpServer.BODY, 1000)); // a leftover from last time
        Recording rec = new Recording();

        Path file = downloader().fetch(List.of(server.uri("/noranges")), target(), -1, null, rec, new CancellationToken());

        assertArrayEquals(TestHttpServer.BODY, Files.readAllBytes(file), "not the leftover plus the body");
        assertEquals(List.of(0L), rec.starts, "200 means start over");
    }

    @Test
    void existingPartIsResumedWhenTheServerAllows() throws Exception {
        Path part = HttpDownloader.partFile(target());
        Files.createDirectories(part.getParent());
        Files.write(part, Arrays.copyOf(TestHttpServer.BODY, 500_000));
        Recording rec = new Recording();

        downloader().fetch(List.of(server.uri("/ok")), target(), -1, null, rec, new CancellationToken());

        assertArrayEquals(TestHttpServer.BODY, Files.readAllBytes(target()));
        assertEquals(List.of(500_000L), rec.starts);
        assertEquals(TestHttpServer.BODY_SIZE, rec.progress.get(0).total(), "total from Content-Range");
    }

    @Test
    void missingMirrorIsSkippedAndTheNextOneUsed() throws Exception {
        Recording rec = new Recording();
        downloader().fetch(List.of(server.uri("/missing"), server.uri("/ok")), target(), -1, null, rec, new CancellationToken());
        assertArrayEquals(TestHttpServer.BODY, Files.readAllBytes(target()));
        assertEquals(List.of(), rec.retries, "a 404 is not retried");
    }

    @Test
    void serverErrorIsRetried() throws Exception {
        server.flakyRequests.set(0);
        Recording rec = new Recording();
        downloader().fetch(List.of(server.uri("/flaky")), target(), -1, null, rec, new CancellationToken());
        assertArrayEquals(TestHttpServer.BODY, Files.readAllBytes(target()));
        assertEquals(1, rec.retries.size());
        assertTrue(rec.retries.get(0).contains("HTTP 500"), rec.retries.get(0));
    }

    @Test
    void allMirrorsFailingListsEveryAttempt() {
        IOException e = assertThrows(IOException.class, () -> downloader().fetch(
                List.of(server.uri("/missing"), server.uri("/missing")), target(), -1, null, DownloadListener.NOOP, new CancellationToken()));
        assertTrue(e.getMessage().startsWith("all mirrors failed: "), e.getMessage());
        assertTrue(e.getMessage().contains("/missing: HTTP 404"), e.getMessage());
        assertFalse(Files.exists(target()));
    }

    @Test
    void redirectsAreFollowed() throws Exception {
        downloader().fetch(List.of(server.uri("/redirect")), target(), -1, null, DownloadListener.NOOP, new CancellationToken());
        assertArrayEquals(TestHttpServer.BODY, Files.readAllBytes(target()));
    }

    @Test
    void chunkedResponseHasUnknownTotalUnlessTheManifestKnows() throws Exception {
        Recording rec = new Recording();
        downloader().fetch(List.of(server.uri("/chunked")), target(), -1, null, rec, new CancellationToken());
        assertEquals(-1, rec.progress.get(0).total());
        assertEquals(-1.0, rec.progress.get(0).fraction());

        Recording withSize = new Recording();
        downloader().fetch(List.of(server.uri("/chunked")), target(), TestHttpServer.BODY_SIZE, null, withSize, new CancellationToken());
        assertEquals(TestHttpServer.BODY_SIZE, withSize.progress.get(0).total(), "manifest size fills in");
    }

    @Test
    void stalledConnectionIsGivenUpAndRetried() {
        HttpDownloader impatient = new HttpDownloader(
                new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(300)), Backoff.NONE, 2);
        Recording rec = new Recording();
        IOException e = assertThrows(IOException.class, () -> impatient.fetch(
                List.of(server.uri("/stall")), target(), -1, null, rec, new CancellationToken()));
        assertTrue(e.getMessage().contains("no data for 0 s"), e.getMessage());
        assertEquals(1, rec.retries.size(), "second attempt also stalls, then the mirror is exhausted");
    }

    @Test
    void cancelStopsTheDownloadAndKeepsThePart() throws Exception {
        CancellationToken token = new CancellationToken();
        CountDownLatch someProgress = new CountDownLatch(2);
        DownloadListener listener = new DownloadListener() {
            @Override
            public void progress(DownloadProgress p) {
                someProgress.countDown();
            }
        };
        Thread canceller = new Thread(() -> {
            try {
                someProgress.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            token.cancel();
        });
        canceller.start();

        assertThrows(CancelledException.class, () -> downloader().fetch(
                List.of(server.uri("/slow")), target(), -1, null, listener, token));
        canceller.join();
        Path part = HttpDownloader.partFile(target());
        assertTrue(Files.exists(part), "part kept for resume");
        assertTrue(Files.size(part) > 0 && Files.size(part) < TestHttpServer.BODY_SIZE, "" + Files.size(part));
        assertFalse(Files.exists(target()));
    }
}
