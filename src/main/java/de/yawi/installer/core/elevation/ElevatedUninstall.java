package de.yawi.installer.core.elevation;

import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.ProgressListener;
import de.yawi.installer.core.engine.Uninstaller;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.ElevationUnavailableException;
import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The parent's side of an elevated uninstall: the sibling of
 * {@link ElevatedRun} for a run that removes rather than installs. It writes the
 * hand-over directory (manifest and {@link UninstallPlan}), starts the child
 * through the platform's {@link ElevationStrategy} with
 * {@code --elevated-uninstall=<dir>}, relays the child's output lines, and reads
 * the {@link Uninstaller.Report} from {@code result.xml}. The child never touches
 * the register - the unelevated caller removes the entry afterwards.
 */
public final class ElevatedUninstall implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ElevatedUninstall.class);

    static final Duration POLL = Duration.ofMillis(200);
    static final Duration CLOSE_GRACE = Duration.ofSeconds(10);
    private static final int STDERR_LIMIT = 4000;

    private final ElevationStrategy strategy;
    private final List<String> command;
    private final ProgressListener listener;
    private final CancellationToken token;
    private final Path workdir;
    private Process process;
    private final StringBuilder stderr = new StringBuilder();
    private long eventsOffset;
    private final StringBuilder pending = new StringBuilder();
    private Runnable cancelHook;
    private volatile boolean cancelSent;

    private ElevatedUninstall(ElevationStrategy strategy, List<String> command, ProgressListener listener,
                             CancellationToken token, Path workdir) {
        this.strategy = strategy;
        this.command = command;
        this.listener = listener;
        this.token = token;
        this.workdir = workdir;
    }

    /** Writes the hand-over directory. Nothing is started yet. */
    public static ElevatedUninstall prepare(Elevation elevation, Platform platform, InstallManifest manifest,
                                            UninstallPlan plan, ProgressListener listener, CancellationToken token) {
        ElevationStrategy strategy = elevation.strategy(platform).orElseThrow(() -> new ElevationUnavailableException(
                "administrator rights are needed to remove " + plan.destination()
                        + " but no elevation strategy is available"));
        Path workdir;
        try {
            workdir = HandoverDir.create(de.yawi.installer.core.engine.ExecutionContext.workDir(platform));
            Files.write(workdir.resolve(HandoverDir.MANIFEST), manifest.xmlBytes());
            HandoverDir.restrictFile(workdir.resolve(HandoverDir.MANIFEST));
            plan.write(workdir.resolve(UninstallPlan.NAME));
        } catch (IOException e) {
            throw new InstallerException(ErrorCode.GENERAL, "cannot prepare the hand-over directory: "
                    + e.getMessage(), e);
        }
        LOG.info("Hand-over directory {} for an elevated uninstall of {} via {}", workdir, plan.destination(),
                strategy.name());
        return new ElevatedUninstall(strategy, elevation.command(), listener, token, workdir);
    }

    public Path workdir() {
        return workdir;
    }

    /** Starts the child and waits for {@code ready}: the rights prompt appears now. */
    public void start() {
        token.checkpoint();
        List<String> full = new ArrayList<>(command);
        full.add("--elevated-uninstall=" + workdir);
        if (strategy.elevates()) {
            listener.elevationRequested();
        }
        try {
            process = strategy.start(full, Path.of(System.getProperty("user.dir", ".")));
        } catch (IOException e) {
            throw new ElevationUnavailableException("cannot start the elevated process via " + strategy.name()
                    + ": " + e.getMessage(), e.toString());
        }
        readStderr(process.getErrorStream());
        cancelHook = token.onCancel(this::sendCancel);
        try {
            boolean ready = false;
            while (!ready) {
                if (!process.isAlive()) {
                    drainEvents();
                    throw endedWithoutResult(process.exitValue());
                }
                ready = drainEvents();
                if (!ready) {
                    pause();
                }
            }
        } catch (RuntimeException e) {
            kill();
            throw e;
        }
        LOG.info("Elevated uninstall process {} is ready", process.pid());
    }

    /** The child may run now. */
    public void go() {
        touch(HandoverDir.GO);
    }

    /** Relays output until the child ends and returns the {@link Uninstaller.Report} it wrote. */
    public Uninstaller.Report await() {
        while (process.isAlive()) {
            drainEvents();
            pause();
        }
        drainEvents();
        int exit = process.exitValue();
        Optional<UninstallResult> result;
        try {
            result = UninstallResult.read(workdir.resolve(HandoverDir.RESULT));
        } catch (IOException e) {
            throw new InstallerException(ErrorCode.GENERAL, "unreadable result of the elevated uninstall: "
                    + e.getMessage(), e);
        }
        if (result.isEmpty()) {
            throw endedWithoutResult(exit);
        }
        if (result.get().failure().isPresent()) {
            throw result.get().failure().get().toException();
        }
        LOG.info("Elevated uninstall ended with {} (result: {})", exit,
                result.get().succeeded() ? "ok" : "with failures");
        return result.get().report().orElseThrow(() ->
                new InstallerException(ErrorCode.GENERAL, "the elevated uninstall reported neither result nor failure", null));
    }

    private InstallerException endedWithoutResult(int exit) {
        String tail = stderr.toString().strip();
        return switch (strategy.classify(exit, tail)) {
            case DECLINED -> new CancelledException("elevation prompt declined (" + strategy.name() + " exit " + exit + ")", null);
            case FAILED -> new ElevationUnavailableException(strategy.name() + " could not elevate (exit " + exit + ")",
                    tail.isEmpty() ? null : tail);
            case NORMAL -> new InstallerException(ErrorCode.GENERAL,
                    "the elevated uninstall ended with exit " + exit + " without reporting a result", null,
                    tail.isEmpty() ? "exit " + exit : tail);
        };
    }

    // --- events (only ready and output matter for an uninstall) ------------------

    private boolean drainEvents() {
        boolean ready = false;
        Path events = workdir.resolve(HandoverDir.EVENTS);
        try (FileChannel channel = FileChannel.open(events, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size > eventsOffset) {
                ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(size - eventsOffset, 1 << 20));
                channel.position(eventsOffset);
                int read = channel.read(buffer);
                if (read > 0) {
                    eventsOffset += read;
                    pending.append(new String(buffer.array(), 0, read, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            LOG.warn("Cannot read {}: {}", events, e.toString());
            return false;
        }
        int newline;
        while ((newline = pending.indexOf("\n")) >= 0) {
            String line = pending.substring(0, newline);
            pending.delete(0, newline + 1);
            if (dispatch(line)) {
                ready = true;
            }
        }
        return ready;
    }

    /** @return whether the line was {@code ready} */
    private boolean dispatch(String line) {
        Optional<List<String>> decoded = EventLine.decode(line);
        if (decoded.isEmpty() || decoded.get().isEmpty()) {
            return false;
        }
        List<String> f = decoded.get();
        try {
            switch (f.get(0)) {
                case EventLine.READY -> {
                    return true;
                }
                case EventLine.OUTPUT -> listener.output(f.get(1));
                case EventLine.UNINSTALL_STARTED -> listener.uninstallStarted(Integer.parseInt(f.get(1)));
                case EventLine.UNINSTALL_PROGRESS -> listener.uninstallProgress(Integer.parseInt(f.get(1)),
                        Integer.parseInt(f.get(2)), f.get(3));
                default -> LOG.debug("Ignoring event {} during an uninstall", f.get(0));
            }
        } catch (RuntimeException e) {
            LOG.warn("Malformed event line ignored: {} ({})", line, e.toString());
        }
        return false;
    }

    // --- process plumbing --------------------------------------------------------

    private void sendCancel() {
        if (!cancelSent) {
            cancelSent = true;
            touch(HandoverDir.CANCEL);
            LOG.info("Cancel marker written for the elevated uninstall");
        }
    }

    private void touch(String name) {
        try {
            Files.writeString(workdir.resolve(name), Instant.now().toString());
        } catch (IOException e) {
            LOG.warn("Cannot write marker {}: {}", name, e.toString());
        }
    }

    private void pause() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException e) {
            sendCancel();
        }
    }

    private void readStderr(InputStream in) {
        if (in == null) {
            return;
        }
        Thread reader = new Thread(() -> {
            try (in) {
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    synchronized (stderr) {
                        stderr.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                        if (stderr.length() > STDERR_LIMIT) {
                            stderr.delete(0, stderr.length() - STDERR_LIMIT);
                        }
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "elevated-uninstall-stderr");
        reader.setDaemon(true);
        reader.start();
    }

    private void kill() {
        if (process != null && process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    @Override
    public void close() {
        if (cancelHook != null) {
            cancelHook.run();
        }
        if (process != null && process.isAlive()) {
            sendCancel();
            try {
                process.waitFor(CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        kill();
        Path log = workdir.resolve(HandoverDir.LOG);
        try {
            if (Files.isRegularFile(log) && Files.size(log) > 0) {
                Path copy = LogSetup.currentLogFile().resolveSibling(ElevatedRun.COPIED_LOG_NAME);
                Files.copy(log, copy, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Log of the elevated uninstall kept at {}", copy);
            }
        } catch (IOException e) {
            LOG.debug("Could not keep the elevated log: {}", e.toString());
        }
        HandoverDir.delete(workdir);
    }
}
