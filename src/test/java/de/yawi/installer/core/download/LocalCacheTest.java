package de.yawi.installer.core.download;

import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.error.IntegrityException;
import de.yawi.installer.core.manifest.Provider;
import de.yawi.installer.core.manifest.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E15 update contract: {@code --cache} with a SHA256SUMS index, matched by the source's checksum. */
class LocalCacheTest {

    /** sha256 of "hello\n". */
    static final String HELLO = "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03";
    static final String OTHER = "1111111111111111111111111111111111111111111111111111111111111111";
    static final DownloadListener SILENT = new DownloadListener() { };

    @TempDir
    Path tmp;

    @Test
    void indexIsParsedInSha256sumFormat() {
        Map<String, String> index = LocalCache.parseIndex("""
                # comment
                5891B5B522D5DF086D0FF0B110FBD9D21BB4FC7163AF34D08286A2E846F6BE03  Test/testpayload-1.0.0-linux-x86_64.tar.gz
                0000000000000000000000000000000000000000000000000000000000000001 *installer/x.zip
                not a line
                """);
        assertEquals(2, index.size());
        assertEquals("Test/testpayload-1.0.0-linux-x86_64.tar.gz", index.get(HELLO));
        assertEquals("installer/x.zip", index.get("0000000000000000000000000000000000000000000000000000000000000001"));
    }

    @Test
    void hitIsVerifiedAndReturned() throws IOException {
        Path file = write("launcher/a.txt", "hello\n", HELLO);
        LocalCache cache = LocalCache.open(tmp);
        Optional<Path> hit = cache.lookup(source("a", HELLO), SILENT, new CancellationToken());
        assertEquals(Optional.of(file), hit);
        assertTrue(Files.exists(file), "the cache is never written or cleaned by the installer");
    }

    @Test
    void noChecksumNoEntryOrMissingFileMeansNoHit() throws IOException {
        write("launcher/a.txt", "hello\n", HELLO);
        Files.writeString(tmp.resolve(LocalCache.INDEX_FILE), HELLO + "  launcher/a.txt\n"
                + OTHER + "  launcher/gone.txt\n");
        LocalCache cache = LocalCache.open(tmp);
        assertEquals(Optional.empty(), cache.lookup(source("x", null), SILENT, new CancellationToken()));
        assertEquals(Optional.empty(), cache.lookup(source("x",
                "2222222222222222222222222222222222222222222222222222222222222222"), SILENT, new CancellationToken()));
        assertEquals(Optional.empty(), cache.lookup(source("x", OTHER), SILENT, new CancellationToken()));
    }

    @Test
    void wrongContentIsAnIntegrityFailureNotAMiss() throws IOException {
        write("launcher/a.txt", "tampered\n", HELLO);
        LocalCache cache = LocalCache.open(tmp);
        assertThrows(IntegrityException.class, () -> cache.lookup(source("a", HELLO), SILENT, new CancellationToken()));
    }

    @Test
    void entriesOutsideTheCacheAreIgnored() throws IOException {
        Files.writeString(tmp.resolve(LocalCache.INDEX_FILE), HELLO + "  ../../etc/passwd\n");
        LocalCache cache = LocalCache.open(tmp);
        assertEquals(Optional.empty(), cache.lookup(source("a", HELLO), SILENT, new CancellationToken()));
    }

    @Test
    void missingDirectoryOrIndexIsAnIoError() {
        assertThrows(IOException.class, () -> LocalCache.open(tmp.resolve("nope")));
        assertThrows(IOException.class, () -> LocalCache.open(tmp));
    }

    private Path write(String relative, String content, String listedAs) throws IOException {
        Path file = tmp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve(LocalCache.INDEX_FILE), listedAs + "  " + relative + "\n");
        return file;
    }

    private static Source source(String id, String sha256) {
        return new Source(id, -1, sha256, List.of(new Provider.Http(List.of(URI.create("http://127.0.0.1:1/x")))), null);
    }
}
