package bt.metainfo;

import bt.torrent.maker.TorrentBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** the bencoding models bt loads from the classpath root are present and usable. */
class MetadataServiceTest {

    @TempDir
    Path tmp;

    @Test
    void torrentBuiltHereIsReadBack() throws Exception {
        byte[] payload = new byte[100_000];
        new Random(7).nextBytes(payload);
        Path file = tmp.resolve("data.bin");
        Files.write(file, payload);

        byte[] metainfo = new TorrentBuilder()
                .rootPath(tmp)
                .addFile(file)
                .pieceSize(16 * 1024)
                .announce("udp://127.0.0.1:1/announce")
                .createdBy("yawi-installer test")
                .build();

        Torrent torrent = new MetadataService().fromInputStream(new ByteArrayInputStream(metainfo));

        assertEquals("data.bin", torrent.getName());
        assertEquals(payload.length, torrent.getSize());
        assertEquals(16 * 1024, torrent.getChunkSize());
        assertEquals(1, torrent.getFiles().size());
        assertTrue(torrent.getAnnounceKey().isPresent());
        assertEquals(TorrentId.length(), torrent.getTorrentId().getBytes().length);
    }
}
