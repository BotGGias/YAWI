package de.yawi.installer.core.download;

import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.error.CancelledException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in check against a real server: {@code -Dyawi.network.url=<url to a file of a few MB>}.
 * Downloads, cancels after 5 MiB, resumes and verifies the size against Content-Length.
 * Skipped without the property, so the normal build never touches the network.
 */
class HttpDownloaderNetworkIT {

    @TempDir
    Path tmp;

    @Test
    void downloadCancelAndResume() throws Exception {
        String url = System.getProperty("yawi.network.url");
        assumeTrue(url != null && !url.isBlank(), "set -Dyawi.network.url to run");
        URI uri = URI.create(url);
        long expected = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
                .headers().firstValueAsLong("Content-Length").orElse(-1);
        assumeTrue(expected > 6L << 20, "need a file over 6 MiB with Content-Length, got " + expected);

        Path target = tmp.resolve(SourceResolver.fileName(uri, "file"));
        HttpDownloader downloader = new HttpDownloader();
        CancellationToken token = new CancellationToken();
        List<Long> starts = new ArrayList<>();
        DownloadListener listener = new DownloadListener() {
            @Override
            public void started(URI u, long resumeFrom) {
                starts.add(resumeFrom);
            }

            @Override
            public void progress(DownloadProgress p) {
                if (p.bytes() >= 5L << 20) {
                    token.cancel();
                }
            }
        };
        assertThrows(CancelledException.class, () -> downloader.fetch(List.of(uri), target, expected, null, listener, token));
        long partial = Files.size(HttpDownloader.partFile(target));
        assertTrue(partial >= 5L << 20 && partial < expected, "cancelled mid-way: " + partial);

        List<DownloadProgress> resumed = new ArrayList<>();
        downloader.fetch(List.of(uri), target, expected, null, new DownloadListener() {
            @Override
            public void started(URI u, long resumeFrom) {
                starts.add(resumeFrom);
            }

            @Override
            public void progress(DownloadProgress p) {
                resumed.add(p);
            }
        }, new CancellationToken());

        assertEquals(expected, Files.size(target));
        assertEquals(2, starts.size());
        assertEquals(partial, starts.get(1), "second run resumed with a 206 at the part's size");
        assertEquals(expected, resumed.get(resumed.size() - 1).total());
        assertTrue(resumed.get(resumed.size() - 1).bytesPerSecond() > 0);
    }
}
