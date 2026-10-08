package de.yawi.installer.core.download;

import de.yawi.installer.core.engine.CancellationToken;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * Fetches one file. Implementations: {@link HttpDownloader}; BitTorrent with E08.
 */
public interface Downloader {

    /**
     * Downloads to {@code target}, trying {@code mirrors} in order. A partial
     * file next to the target ({@code <name>.part}) is resumed when the
     * server allows it.
     *
     * @param expectedSize   the manifest's size, {@code -1} if unknown; used
     *                       for progress when the server sends no length
     * @param expectedSha256 the manifest's checksum, digested while the file
     *                       is written (E10-S01); {@code null} = not checked
     * @return {@code target}
     * @throws IOException if every mirror failed
     * @throws de.yawi.installer.core.error.IntegrityException if the complete
     *         file does not match {@code expectedSha256}; it is deleted
     * @throws de.yawi.installer.core.error.CancelledException on cancel; the
     *         part file stays for the next attempt
     */
    Path fetch(List<URI> mirrors, Path target, long expectedSize, String expectedSha256, DownloadListener listener,
               CancellationToken cancellation) throws IOException;
}
