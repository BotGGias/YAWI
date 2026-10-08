package de.yawi.installer.core.download;

import bt.Bt;
import bt.data.file.FileSystemStorage;
import bt.metainfo.MetadataService;
import bt.metainfo.Torrent;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.torrent.TorrentSessionState;
import de.yawi.installer.core.download.torrent.NoPeersException;
import de.yawi.installer.core.download.torrent.TorrentRuntime;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.IntegrityException;
import de.yawi.installer.core.integrity.ChecksumVerifier;
import de.yawi.installer.core.manifest.Provider;
import de.yawi.installer.core.manifest.ResourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Downloads one source over BitTorrent with the vendored bt library
 * . The counterpart of {@link HttpDownloader}: same listener, same
 * exceptions, same result - a complete, checksum-verified file - but
 * addressed by a magnet link or a torrent file instead of URLs, which is why
 * it does not share the {@link Downloader} signature.
 *
 * <p>Data goes to {@code targetDir/<torrent name>}; bt recognises what an
 * earlier run left there and continues. Only single-file torrents
 * are accepted: a source is one archive. Without a connected peer for the
 * provider's {@code peerTimeout} the download gives up with
 * {@link NoPeersException} so that the next provider gets its turn
 * . bt verifies every piece; the whole file is checked against the
 * manifest's sha256 afterwards. Not final so that tests can
 * stand in for it.
 */
public class TorrentDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(TorrentDownloader.class);

    private static final long STATE_PERIOD_MILLIS = 250;
    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L;

    private final TorrentRuntime runtime;
    private final MetadataService metadata = new MetadataService();

    public TorrentDownloader(TorrentRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * @param targetDir      where the torrent's file is written; created if missing
     * @param expectedSize   the manifest's size, {@code -1} if unknown (only for logging - the torrent knows)
     * @param expectedSha256 the manifest's checksum, or {@code null} = not checked
     * @return the downloaded file
     * @throws NoPeersException     if no peer connected within the provider's {@code peerTimeout}
     * @throws IOException          if the torrent is unusable (multi-file, unreadable) or bt failed
     * @throws IntegrityException   if the complete file does not match {@code expectedSha256}; it is deleted
     * @throws CancelledException   on cancel; the data stays for the next attempt
     */
    public Path fetch(Provider.Torrent provider, Path targetDir, long expectedSize, String expectedSha256,
                      DownloadListener listener, CancellationToken cancellation) throws IOException {
        Files.createDirectories(targetDir);
        cancellation.checkpoint();
        BtRuntime rt = runtime.runtime(provider);

        Session session = new Session(provider, targetDir, listener, cancellation);
        var builder = Bt.client(rt)
                .storage(new FileSystemStorage(targetDir))
                .sequentialSelector()
                .stopWhenDownloaded()
                .afterTorrentFetched(session::torrentFetched);
        if (provider.magnet() != null) {
            builder.magnet(provider.magnet());
        } else {
            builder.torrent(() -> readTorrent(provider.torrentFile(), session));
        }
        BtClient client = builder.build();
        session.client = client;

        Runnable unhook = cancellation.onCancel(client::stop);
        try {
            LOG.info("Torrent download of {} to {} (peer timeout {})", session.describe(), targetDir,
                    de.yawi.installer.core.manifest.Durations.format(provider.peerTimeout()));
            CompletableFuture<?> done = client.startAsync(session::onState, STATE_PERIOD_MILLIS);
            try {
                // Waited in slices so that the peer clock runs on this thread too - bt's
                // listener executor dies quietly if a callback ever throws.
                while (true) {
                    try {
                        done.get(STATE_PERIOD_MILLIS * 2, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException e) {
                        session.checkPeerTimeout();
                    }
                }
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof UncheckedIOException unchecked) {
                    throw unchecked.getCause();
                }
                throw new IOException("torrent failed: " + cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException("interrupted while downloading " + session.describe(), e);
            }
        } finally {
            unhook.run();
            client.stop();
            // The runtime is disposable: a second session of the same torrent on one bt
            // runtime either finds the torrent still registered (after a stop from outside
            // bt's processing thread keeps waiting for pieces) or inherits deactivated peer
            // connections and never gets data. Rebuilding takes milliseconds; the next
            // download gets a fresh runtime on the same port.
            runtime.shutdown();
        }

        if (cancellation.isCancelled()) {
            throw new CancelledException("torrent download cancelled, keeping " + targetDir, null);
        }
        if (session.failure != null) {
            throw session.failure;
        }
        Torrent torrent = session.torrent.get();
        if (!session.completed()) {
            TorrentSessionState last = session.lastState.get();
            throw new IOException("torrent stopped before the download completed"
                    + (last == null ? "" : " (" + last.getPiecesRemaining() + " piece(s) left)"));
        }
        Path file = targetDir.resolve(String.join("/", torrent.getFiles().get(0).getPathElements()));
        if (!Files.isRegularFile(file)) {
            throw new IOException("torrent completed but " + file + " is missing");
        }
        LOG.info("Torrent {} complete: {} ({} bytes)", torrent.getName(), file, Files.size(file));
        session.reportFinal(torrent);

        if (expectedSha256 != null) {
            verify(file, torrent, expectedSha256, listener, cancellation);
        }
        return file;
    }

    /** bt checked the pieces against the torrent; this checks the file against the manifest. */
    private static void verify(Path file, Torrent torrent, String expectedSha256, DownloadListener listener,
                               CancellationToken cancellation) throws IOException {
        listener.verifying(torrent.getName(), torrent.getSize());
        try {
            ChecksumVerifier.verifyFile(file, expectedSha256, bytes -> {
                cancellation.checkpoint();
                listener.progress(new DownloadProgress(bytes, torrent.getSize(), 0, -1));
            });
        } catch (IntegrityException e) {
            LOG.warn("Checksum mismatch, deleting {}: {}", file, e.getMessage());
            Files.deleteIfExists(file);
            throw e;
        }
    }

    /** Called by bt on its own thread; the failure is remembered because bt only logs it. */
    private Torrent readTorrent(String torrentFile, Session session) {
        try (InputStream in = ResourceRef.open(torrentFile)) {
            return metadata.fromInputStream(in);
        } catch (IOException | RuntimeException e) {
            IOException failure = new IOException("torrent file " + torrentFile + " unreadable: " + e.getMessage(), e);
            session.fail(failure);
            throw new UncheckedIOException(failure);
        }
    }

    /** What one download tracks between bt's callbacks and the caller's listener. */
    private static final class Session {
        final Provider.Torrent provider;
        final Path targetDir;
        final DownloadListener listener;
        final CancellationToken cancellation;
        final AtomicReference<Torrent> torrent = new AtomicReference<>();
        final AtomicReference<TorrentSessionState> lastState = new AtomicReference<>();
        final SpeedMeter speed = new SpeedMeter();
        final AtomicLong lastPeerSeen = new AtomicLong(System.nanoTime());
        final AtomicLong lastReport = new AtomicLong();
        volatile IOException failure;
        volatile boolean started;
        volatile BtClient client;

        Session(Provider.Torrent provider, Path targetDir, DownloadListener listener, CancellationToken cancellation) {
            this.provider = provider;
            this.targetDir = targetDir;
            this.listener = listener;
            this.cancellation = cancellation;
        }

        String describe() {
            return provider.magnet() != null ? provider.magnet() : provider.torrentFile();
        }

        URI uri() {
            return provider.magnet() != null ? URI.create(provider.magnet().replace(" ", "%20"))
                    : URI.create("torrent:" + provider.torrentFile().replace(" ", "%20"));
        }

        void torrentFetched(Torrent t) {
            if (t.getFiles().size() != 1) {
                fail(new IOException("torrent " + t.getName() + " has " + t.getFiles().size()
                        + " files; a source must be a single file"));
                return;
            }
            LOG.info("Torrent metadata: {} ({} bytes, {} pieces of {})", t.getName(), t.getSize(),
                    (t.getSize() + t.getChunkSize() - 1) / t.getChunkSize(), t.getChunkSize());
            torrent.set(t);
        }

        /** Called by bt every {@link #STATE_PERIOD_MILLIS}; must never throw, or bt stops calling. */
        void onState(TorrentSessionState state) {
            try {
                lastState.set(state);
                if (!state.getConnectedPeers().isEmpty()) {
                    lastPeerSeen.set(System.nanoTime());
                }
                Torrent t = torrent.get();
                if (t == null || state.getLeft() == TorrentSessionState.UNKNOWN) {
                    return; // still fetching metadata (magnet), or no data descriptor (yet / any more)
                }
                long total = t.getSize();
                long bytes = total - state.getLeft();
                if (!started) {
                    started = true;
                    // Whatever bt found on disk and verified counts as resumed.
                    listener.started(uri(), bytes);
                    speed.update(bytes);
                }
                long now = System.nanoTime();
                if (now - lastReport.get() > PROGRESS_INTERVAL_NANOS) {
                    lastReport.set(now);
                    speed.update(bytes);
                    listener.progress(new DownloadProgress(bytes, total, speed.bytesPerSecond(),
                            speed.etaSeconds(total - bytes)));
                }
            } catch (RuntimeException e) {
                LOG.debug("Torrent state callback failed: {}", e.toString());
            }
        }

        /** On the downloading thread: no peer for longer than the provider allows means giving up. */
        void checkPeerTimeout() {
            TorrentSessionState state = lastState.get();
            boolean done = state != null && torrent.get() != null && state.getLeft() != TorrentSessionState.UNKNOWN
                    && state.getPiecesRemaining() == 0;
            if (!done && System.nanoTime() - lastPeerSeen.get() > provider.peerTimeout().toNanos()) {
                fail(new NoPeersException(provider.peerTimeout()));
            }
        }

        /** True once bt has every piece. */
        boolean completed() {
            TorrentSessionState state = lastState.get();
            return failure == null && torrent.get() != null && state != null
                    && state.getLeft() != TorrentSessionState.UNKNOWN && state.getPiecesRemaining() == 0;
        }

        void reportFinal(Torrent t) {
            speed.update(t.getSize());
            listener.progress(new DownloadProgress(t.getSize(), t.getSize(), speed.bytesPerSecond(), 0));
        }

        void fail(IOException e) {
            if (failure == null) {
                failure = e;
                LOG.warn("Torrent download of {} stopped: {}", describe(), e.getMessage());
                BtClient c = client;
                if (c != null) {
                    c.stop();
                }
            }
        }
    }
}
