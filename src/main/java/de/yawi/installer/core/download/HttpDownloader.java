package de.yawi.installer.core.download;

import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.IntegrityException;
import de.yawi.installer.core.integrity.ChecksumVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads over HTTP(S) with the JDK's {@link HttpClient} (E07-S02): mirrors
 * in order with retries and backoff, resume through {@code Range} when the
 * server supports it, a stall watchdog in place of the body read timeout the
 * client lacks, progress with speed and remaining time, and a cancel that
 * keeps the part file for next time.
 *
 * <p>Redirects follow {@link HttpClient.Redirect#NORMAL}, which never goes
 * from HTTPS to HTTP. The system proxy is used when
 * {@code java.net.useSystemProxies} is set before the first request (the
 * launcher does that).
 */
public final class HttpDownloader implements Downloader {

    private static final Logger LOG = LoggerFactory.getLogger(HttpDownloader.class);

    public static final String PART_SUFFIX = ".part";
    private static final int BUFFER = 64 * 1024;
    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)");

    /**
     * @param connect time to establish a connection
     * @param headers time until the response headers arrive
     * @param stall   longest pause without body bytes before the attempt is given up
     */
    public record Timeouts(Duration connect, Duration headers, Duration stall) {
        public static final Timeouts DEFAULT = new Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    /** Pause before retry {@code attempt} (1-based); tests use {@link #NONE}. */
    @FunctionalInterface
    public interface Backoff {
        Backoff NONE = attempt -> Duration.ZERO;
        /** 1 s, 2 s, 4 s, ... */
        Backoff EXPONENTIAL = attempt -> Duration.ofSeconds(1L << Math.min(attempt - 1, 5));

        Duration pause(int attempt);
    }

    /** A 4xx that will not improve by retrying; try the next mirror. */
    private static final class MirrorUnusable extends IOException {
        MirrorUnusable(String message) {
            super(message);
        }
    }

    private final HttpClient client;
    private final Timeouts timeouts;
    private final Backoff backoff;
    private final int attemptsPerMirror;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "download-watchdog");
        t.setDaemon(true);
        return t;
    });

    public HttpDownloader() {
        this(Timeouts.DEFAULT, Backoff.EXPONENTIAL, 3);
    }

    public HttpDownloader(Timeouts timeouts, Backoff backoff, int attemptsPerMirror) {
        this(HttpClient.newBuilder()
                .connectTimeout(timeouts.connect())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), timeouts, backoff, attemptsPerMirror);
    }

    public HttpDownloader(HttpClient client, Timeouts timeouts, Backoff backoff, int attemptsPerMirror) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        if (attemptsPerMirror < 1) {
            throw new IllegalArgumentException("attemptsPerMirror");
        }
        this.attemptsPerMirror = attemptsPerMirror;
    }

    public static Path partFile(Path target) {
        return target.resolveSibling(target.getFileName() + PART_SUFFIX);
    }

    @Override
    public Path fetch(List<URI> mirrors, Path target, long expectedSize, String expectedSha256,
                      DownloadListener listener, CancellationToken cancellation) throws IOException {
        if (mirrors.isEmpty()) {
            throw new IllegalArgumentException("no mirrors");
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Path part = partFile(target);
        List<String> failures = new ArrayList<>();
        for (URI url : mirrors) {
            if ("http".equalsIgnoreCase(url.getScheme())) {
                LOG.warn("Downloading over plain HTTP (not encrypted): {}", url);
            }
            for (int attempt = 1; attempt <= attemptsPerMirror; attempt++) {
                cancellation.checkpoint();
                try {
                    ChecksumVerifier verifier = expectedSha256 == null ? null : ChecksumVerifier.sha256(expectedSha256);
                    attempt(url, part, expectedSize, verifier, listener, cancellation);
                    if (verifier != null) {
                        verify(verifier, part, target);
                    }
                    finish(part, target);
                    return target;
                } catch (MirrorUnusable e) {
                    LOG.warn("Mirror {} unusable: {}", url, e.getMessage());
                    failures.add(url + ": " + e.getMessage());
                    break;
                } catch (IOException e) {
                    cancellation.checkpoint();
                    LOG.warn("Download from {} failed (attempt {}/{}): {}", url, attempt, attemptsPerMirror, e.toString());
                    failures.add(url + " (attempt " + attempt + "): " + e.getMessage());
                    if (attempt < attemptsPerMirror) {
                        listener.retry(url, attempt, e);
                        pause(backoff.pause(attempt), cancellation);
                    }
                }
            }
        }
        throw new IOException("all mirrors failed: " + String.join("; ", failures));
    }

    /** The complete file is on disk; it is only kept if the digest agrees. */
    private static void verify(ChecksumVerifier verifier, Path part, Path target) {
        try {
            verifier.verify(target.getFileName().toString());
        } catch (IntegrityException e) {
            LOG.warn("Checksum mismatch, deleting {}: {}", part, e.getMessage());
            try {
                Files.deleteIfExists(part);
                Files.deleteIfExists(target);
            } catch (IOException io) {
                LOG.warn("Could not delete {}: {}", part, io.toString());
            }
            throw e;
        }
    }

    /**
     * One request; returns normally only when the whole body arrived.
     *
     * @param verifier digests the body while it is written, or null; a resumed
     *                 part is fed in first so the digest covers the whole file
     */
    private void attempt(URI url, Path part, long expectedSize, ChecksumVerifier verifier, DownloadListener listener,
                         CancellationToken cancellation) throws IOException {
        long offset = Files.isRegularFile(part) ? Files.size(part) : 0;
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(timeouts.headers()).GET();
        if (offset > 0) {
            request.header("Range", "bytes=" + offset + "-");
        }
        HttpResponse<InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException("interrupted while downloading " + url, e);
        }
        int status = response.statusCode();
        boolean append;
        long total;
        if (status == 206 && offset > 0) {
            append = true;
            long resumedAt = offset;
            total = totalFromContentRange(response).orElseGet(() -> lengthOrExpected(response, expectedSize, resumedAt));
            LOG.info("Resuming {} at byte {} (total {})", url, offset, total);
            if (verifier != null) {
                verifier.feed(part);
            }
        } else if (status == 200) {
            append = false;
            if (offset > 0) {
                LOG.info("Server ignored the range request; starting {} over", url);
            }
            offset = 0;
            total = lengthOrExpected(response, expectedSize, 0);
        } else if (status == 416) {
            response.body().close();
            Files.deleteIfExists(part);
            throw new IOException("HTTP 416, part file discarded");
        } else if (status >= 400 && status < 500 && status != 408 && status != 429) {
            response.body().close();
            throw new MirrorUnusable("HTTP " + status);
        } else {
            response.body().close();
            throw new IOException("HTTP " + status);
        }
        listener.started(url, offset);
        long received = copyBody(response.body(), part, append, offset, total, verifier, listener, cancellation);
        if (total >= 0 && received < total) {
            throw new IOException("connection closed after " + received + " of " + total + " bytes");
        }
    }

    private long copyBody(InputStream body, Path part, boolean append, long offset, long total,
                          ChecksumVerifier verifier, DownloadListener listener, CancellationToken cancellation)
            throws IOException {
        AtomicLong lastRead = new AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean();
        Runnable closeBody = () -> {
            try {
                body.close();
            } catch (IOException ignored) {
                // closing is the point
            }
        };
        Runnable unhook = cancellation.onCancel(closeBody);
        long stallNanos = timeouts.stall().toNanos();
        ScheduledFuture<?> watch = watchdog.scheduleAtFixedRate(() -> {
            if (System.nanoTime() - lastRead.get() > stallNanos) {
                stalled.set(true);
                closeBody.run();
            }
        }, stallNanos / 4, Math.max(stallNanos / 4, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);

        SpeedMeter speed = new SpeedMeter();
        long bytes = offset;
        long lastReport = 0;
        try (InputStream in = body;
             OutputStream out = digesting(Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING), verifier)) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                bytes += n;
                lastRead.set(System.nanoTime());
                if (System.nanoTime() - lastReport > PROGRESS_INTERVAL_NANOS) {
                    lastReport = System.nanoTime();
                    speed.update(bytes);
                    listener.progress(snapshot(bytes, total, speed));
                }
            }
            speed.update(bytes);
            listener.progress(snapshot(bytes, total, speed));
        } catch (IOException e) {
            if (cancellation.isCancelled()) {
                throw new CancelledException("download cancelled, keeping " + part, e);
            }
            if (stalled.get()) {
                throw new IOException("no data for " + timeouts.stall().toSeconds() + " s", e);
            }
            throw e;
        } finally {
            watch.cancel(false);
            unhook.run();
        }
        if (cancellation.isCancelled()) {
            throw new CancelledException("download cancelled, keeping " + part, null);
        }
        return bytes;
    }

    private static OutputStream digesting(OutputStream out, ChecksumVerifier verifier) {
        return verifier == null ? out : verifier.wrap(out);
    }

    private static DownloadProgress snapshot(long bytes, long total, SpeedMeter speed) {
        return new DownloadProgress(bytes, total, speed.bytesPerSecond(), speed.etaSeconds(total < 0 ? -1 : total - bytes));
    }

    private static java.util.Optional<Long> totalFromContentRange(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Range").flatMap(value -> {
            Matcher m = CONTENT_RANGE.matcher(value.trim().toLowerCase(Locale.ROOT));
            if (m.matches() && !m.group(3).equals("*")) {
                return java.util.Optional.of(Long.parseLong(m.group(3)));
            }
            return java.util.Optional.empty();
        });
    }

    /** Content-Length plus what is already on disk, else the manifest's size, else unknown. */
    private static long lengthOrExpected(HttpResponse<?> response, long expectedSize, long offset) {
        return response.headers().firstValueAsLong("Content-Length").stream()
                .map(length -> length + offset)
                .findFirst()
                .orElse(expectedSize > 0 ? expectedSize : -1);
    }

    private static void finish(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        LOG.info("Downloaded {} ({} bytes)", target, Files.size(target));
    }

    private static void pause(Duration duration, CancellationToken cancellation) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException("interrupted while waiting to retry", e);
        }
        cancellation.checkpoint();
    }
}
