package de.yawi.installer.core.download;

import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.error.IntegrityException;
import de.yawi.installer.core.error.SourceUnavailableException;
import de.yawi.installer.core.integrity.ChecksumVerifier;
import de.yawi.installer.core.manifest.Provider;
import de.yawi.installer.core.manifest.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E07-S01-T03: preferred kind first, then the manifest's fallback chain. */
class SourceResolverTest {

    @TempDir
    Path tmp;

    private static final Provider BUNDLED = new Provider.Bundled("classpath:/archives/sample.zip");
    private static final Provider MISSING_BUNDLED = new Provider.Bundled("classpath:/archives/nope.zip");
    private static final Provider HTTP = new Provider.Http(List.of(URI.create("https://example.invalid/a.zip"),
            URI.create("https://mirror.invalid/a.zip")));
    private static final Provider TORRENT = new Provider.Torrent("magnet:?xt=urn:btih:00", null);

    /** A downloader that succeeds or fails on demand and records the calls. */
    private static final class FakeDownloader implements Downloader {
        final List<List<URI>> calls = new ArrayList<>();
        boolean fail;

        @Override
        public Path fetch(List<URI> mirrors, Path target, long expectedSize, String expectedSha256,
                          DownloadListener listener, CancellationToken cancellation) throws IOException {
            calls.add(mirrors);
            if (fail) {
                throw new IOException("all mirrors failed: no route");
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, "downloaded");
            if (expectedSha256 != null) {
                // The real downloader digests while writing; the fake checks afterwards.
                ChecksumVerifier.verifyFile(target, expectedSha256, bytes -> { });
            }
            return target;
        }
    }

    private static Source source(Provider... providers) {
        return new Source("data", 1234, null, List.of(providers), null);
    }

    private static Source source(String sha256, Provider... providers) {
        return new Source("data", -1, sha256, List.of(providers), null);
    }

    private static String sha256(byte[] data) throws Exception {
        return ChecksumVerifier.hex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] resource(String path) throws Exception {
        try (java.io.InputStream in = SourceResolverTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }

    // --- E10-S01: checksums -------------------------------------------------

    @Test
    void bundledDataIsVerifiedBeforeUse() throws Exception {
        FakeDownloader dl = new FakeDownloader();
        List<String> verifying = new ArrayList<>();
        SourceResolver.Resolved r = new SourceResolver(dl).resolve(source(sha256(resource("/archives/sample.zip")), BUNDLED, HTTP),
                ProviderKind.BUNDLED, tmp, new DownloadListener() {
                    @Override
                    public void verifying(String fileName, long total) {
                        verifying.add(fileName);
                    }
                }, new CancellationToken());
        assertEquals(ProviderKind.BUNDLED, r.kind());
        assertEquals(List.of("classpath:/archives/sample.zip"), verifying);
        assertTrue(dl.calls.isEmpty());
    }

    @Test
    void bundledChecksumMismatchFallsBackToTheNextProvider() throws Exception {
        FakeDownloader dl = new FakeDownloader();
        String downloaded = sha256("downloaded".getBytes());
        SourceResolver.Resolved r = new SourceResolver(dl).resolve(source(downloaded, BUNDLED, HTTP),
                ProviderKind.BUNDLED, tmp, DownloadListener.NOOP, new CancellationToken());
        assertEquals(ProviderKind.HTTP, r.kind(), "bundled copy did not match, the download did");
        assertEquals(1, dl.calls.size());
    }

    @Test
    void checksumFailureOnEveryProviderIsAnIntegrityError() {
        FakeDownloader dl = new FakeDownloader();
        IntegrityException e = assertThrows(IntegrityException.class, () -> new SourceResolver(dl).resolve(
                source("0".repeat(64), BUNDLED, HTTP), ProviderKind.HTTP, tmp, DownloadListener.NOOP, new CancellationToken()));
        assertEquals("data", e.fileName());
        assertEquals("http: checksum mismatch\nbundled: checksum mismatch", e.detail().orElseThrow());
    }

    @Test
    void checksumFailureNextToAnUnreachableProviderIsStillAnIntegrityError() {
        FakeDownloader dl = new FakeDownloader();
        dl.fail = true;
        IntegrityException e = assertThrows(IntegrityException.class, () -> new SourceResolver(dl).resolve(
                source("0".repeat(64), BUNDLED, HTTP), ProviderKind.BUNDLED, tmp, DownloadListener.NOOP, new CancellationToken()));
        assertTrue(e.detail().orElseThrow().startsWith("bundled: checksum mismatch\nhttp: all mirrors failed"), e.detail().get());
    }

    @Test
    void bundledIsStreamedNotCopied() throws Exception {
        FakeDownloader dl = new FakeDownloader();
        SourceResolver.Resolved r = new SourceResolver(dl).resolve(source(BUNDLED, HTTP), ProviderKind.BUNDLED, tmp,
                DownloadListener.NOOP, new CancellationToken());
        assertTrue(r.file().isEmpty());
        assertEquals(ProviderKind.BUNDLED, r.kind());
        assertEquals("classpath:/archives/sample.zip", r.describe());
        try (var in = r.open()) {
            assertTrue(in.readAllBytes().length > 0);
        }
        assertEquals(List.of(), dl.calls, "nothing downloaded");
    }

    @Test
    void preferredHttpDownloadsToTheSourceFolder() throws Exception {
        FakeDownloader dl = new FakeDownloader();
        SourceResolver.Resolved r = new SourceResolver(dl).resolve(source(BUNDLED, HTTP), ProviderKind.HTTP, tmp,
                DownloadListener.NOOP, new CancellationToken());
        assertEquals(tmp.resolve("data/a.zip"), r.file().orElseThrow());
        assertEquals("a.zip", r.describe());
        assertEquals(ProviderKind.HTTP, r.kind());
        assertEquals(1, dl.calls.size());
        assertEquals(2, dl.calls.get(0).size(), "both mirrors handed over");
        assertEquals("downloaded", Files.readString(r.file().get()));
    }

    @Test
    void fallsBackToBundledWhenTheDownloadFails() {
        FakeDownloader dl = new FakeDownloader();
        dl.fail = true;
        SourceResolver.Resolved r = new SourceResolver(dl).resolve(source(BUNDLED, HTTP), ProviderKind.HTTP, tmp,
                DownloadListener.NOOP, new CancellationToken());
        assertEquals(ProviderKind.BUNDLED, r.kind());
    }

    @Test
    void everyProviderFailingListsTheAttempts() {
        FakeDownloader dl = new FakeDownloader();
        dl.fail = true;
        SourceUnavailableException e = assertThrows(SourceUnavailableException.class,
                () -> new SourceResolver(dl).resolve(source(MISSING_BUNDLED, HTTP, TORRENT), ProviderKind.HTTP, tmp,
                        DownloadListener.NOOP, new CancellationToken()));
        assertEquals("data", e.sourceId());
        String detail = e.detail().orElseThrow();
        assertTrue(detail.contains("http: all mirrors failed"), detail);
        assertTrue(detail.contains("bundled: "), detail);
        assertTrue(detail.contains("torrent: not available"), detail);
        assertEquals(List.of("http", "bundled", "torrent"),
                detail.lines().map(l -> l.substring(0, l.indexOf(':'))).toList(), "preferred first, then manifest order");
    }

    // --- E08: torrent providers ------------------------------------------

    /** A torrent downloader that succeeds or fails on demand. */
    private static final class FakeTorrents extends TorrentDownloader {
        final List<Provider.Torrent> calls = new ArrayList<>();
        IOException failure;

        FakeTorrents() {
            super(new de.yawi.installer.core.download.torrent.TorrentRuntime());
        }

        @Override
        public Path fetch(Provider.Torrent provider, Path targetDir, long expectedSize, String expectedSha256,
                          DownloadListener listener, CancellationToken cancellation) throws IOException {
            calls.add(provider);
            if (failure != null) {
                throw failure;
            }
            Files.createDirectories(targetDir);
            Path file = targetDir.resolve("data.bin");
            Files.writeString(file, "torrented");
            return file;
        }
    }

    @Test
    void preferredTorrentDownloadsToTheSourceFolder() throws Exception {
        FakeDownloader dl = new FakeDownloader();
        FakeTorrents torrents = new FakeTorrents();
        SourceResolver.Resolved r = new SourceResolver(dl, torrents).resolve(source(BUNDLED, HTTP, TORRENT),
                ProviderKind.TORRENT, tmp, DownloadListener.NOOP, new CancellationToken());
        assertEquals(ProviderKind.TORRENT, r.kind());
        assertEquals(tmp.resolve("data").resolve("data.bin"), r.file().orElseThrow());
        assertEquals("data.bin", r.describe());
        assertEquals(1, torrents.calls.size());
        assertTrue(dl.calls.isEmpty());
    }

    @Test
    void torrentWithoutPeersFallsBackToHttp() throws Exception {
        FakeDownloader dl = new FakeDownloader();
        FakeTorrents torrents = new FakeTorrents();
        torrents.failure = new de.yawi.installer.core.download.torrent.NoPeersException(java.time.Duration.ofSeconds(2));
        SourceResolver.Resolved r = new SourceResolver(dl, torrents).resolve(source(HTTP, TORRENT),
                ProviderKind.TORRENT, tmp, DownloadListener.NOOP, new CancellationToken());
        assertEquals(ProviderKind.HTTP, r.kind(), "next provider after the peer timeout");
        assertEquals(1, dl.calls.size());
    }

    private static final Provider ASKING_TORRENT = new Provider.Torrent("magnet:?xt=urn:btih:00", null, 0,
            java.time.Duration.ofSeconds(2), Provider.Torrent.Fallback.ASK, true, true);

    @Test
    void askingTorrentPutsTheQuestionAndAbortStopsEverything() {
        FakeDownloader dl = new FakeDownloader();
        FakeTorrents torrents = new FakeTorrents();
        torrents.failure = new de.yawi.installer.core.download.torrent.NoPeersException(java.time.Duration.ofSeconds(2));
        List<String> asked = new ArrayList<>();
        List<String> switches = new ArrayList<>();
        DownloadListener listener = new DownloadListener() {
            @Override
            public void fallback(ProviderKind from, ProviderKind to, String reason) {
                switches.add(from + "->" + to + ": " + reason);
            }
        };

        SourceResolver declining = new SourceResolver(dl, torrents, (source, torrent, cause) -> {
            asked.add(source.id() + ": " + cause.getMessage());
            return de.yawi.installer.core.download.torrent.FallbackPrompt.Decision.ABORT;
        });
        assertThrows(de.yawi.installer.core.error.CancelledException.class, () -> declining.resolve(
                source(HTTP, ASKING_TORRENT), ProviderKind.TORRENT, tmp, listener, new CancellationToken()));
        assertEquals(List.of("data: no peers within 2s"), asked);
        assertTrue(dl.calls.isEmpty(), "abort means no HTTP either");
        assertTrue(switches.isEmpty());

        SourceResolver agreeing = declining.withFallbackPrompt((source, torrent, cause) ->
                de.yawi.installer.core.download.torrent.FallbackPrompt.Decision.FALLBACK);
        SourceResolver.Resolved r = agreeing.resolve(source(HTTP, ASKING_TORRENT), ProviderKind.TORRENT, tmp, listener,
                new CancellationToken());
        assertEquals(ProviderKind.HTTP, r.kind());
        assertEquals(List.of("TORRENT->HTTP: no peers within 2s"), switches, "the listener hears about the switch");

        // Nothing to fall back to: no question, the source just fails with the attempt listed.
        asked.clear();
        SourceUnavailableException e = assertThrows(SourceUnavailableException.class, () -> declining.resolve(
                source(ASKING_TORRENT), ProviderKind.TORRENT, tmp, listener, new CancellationToken()));
        assertTrue(asked.isEmpty());
        assertEquals("torrent: no peers within 2s", e.detail().orElseThrow());
    }

    @Test
    void fileNameComesFromTheUrl() {
        assertEquals("a.zip", SourceResolver.fileName(URI.create("https://h/x/a.zip?sig=1"), "id"));
        assertEquals("id", SourceResolver.fileName(URI.create("https://h/"), "id"));
        assertEquals("id", SourceResolver.fileName(URI.create("https://h"), "id"));
    }
}
