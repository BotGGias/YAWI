package de.yawi.installer.core.elevation;

import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.download.SourceResolver;
import de.yawi.installer.core.engine.Engine;
import de.yawi.installer.core.engine.ExecutionPlan;
import de.yawi.installer.core.engine.PlannedStep;
import de.yawi.installer.core.engine.ProgressListener;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.Rollback;
import de.yawi.installer.core.engine.StepOutcome;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.ElevationUnavailableException;
import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.Provider;
import de.yawi.installer.core.manifest.ResourceRef;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.state.InstallationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The parent's side of the elevated segment: writes the
 * hand-over directory, starts the child through the platform's
 * {@link ElevationStrategy}, waits for {@code ready}, gives {@code go} when
 * its turn comes, relays {@code events.log} into the {@link ProgressListener}
 * (and the record entries of a partial run into the parent's record file),
 * turns {@code result.xml} back into an {@link Engine.Result}, and cleans up.
 * Cancellation reaches the child as the {@code cancel} marker; the parent
 * keeps waiting until the child has undone its part.
 */
public final class ElevatedRun implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ElevatedRun.class);

    static final Duration POLL = Duration.ofMillis(200);
    /** How long {@link #close} waits for a child it has just cancelled before killing it. */
    static final Duration CLOSE_GRACE = Duration.ofSeconds(10);
    static final String COPIED_LOG_NAME = "installer-elevated.log";
    private static final int STDERR_LIMIT = 4000;

    private final ElevationStrategy strategy;
    private final List<String> command;
    private final Platform platform;
    private final InstallManifest manifest;
    private final ElevationPlan plan;
    private final ExecutionPlan fullPlan;
    private final ProgressListener listener;
    private final CancellationToken token;
    private final Path workdir;
    private Process process;
    private final StringBuilder stderr = new StringBuilder();
    private long eventsOffset;
    private final StringBuilder pending = new StringBuilder();
    private Runnable cancelHook;
    private volatile boolean cancelSent;

    private ElevatedRun(ElevationStrategy strategy, List<String> command, Platform platform, InstallManifest manifest,
                        ElevationPlan plan, ExecutionPlan fullPlan, ProgressListener listener,
                        CancellationToken token, Path workdir) {
        this.strategy = strategy;
        this.command = command;
        this.platform = platform;
        this.manifest = manifest;
        this.plan = plan;
        this.fullPlan = fullPlan;
        this.listener = listener;
        this.token = token;
        this.workdir = workdir;
    }

    /**
     * Writes the hand-over directory. Nothing is started yet.
     *
     * @param listener receives the child's events (already placed into the whole by the caller)
     */
    public static ElevatedRun prepare(Elevation elevation, Platform platform, InstallManifest manifest,
                                      ElevationPlan plan, ExecutionPlan fullPlan, ProgressListener listener,
                                      CancellationToken token) {
        ElevationStrategy strategy = elevation.strategy(platform).orElseThrow(() -> new ElevationUnavailableException(
                "administrator rights are needed for " + plan.destination() + " but no elevation strategy is available"));
        Path workdir;
        try {
            workdir = HandoverDir.create(de.yawi.installer.core.engine.ExecutionContext.workDir(platform));
            Files.write(workdir.resolve(HandoverDir.MANIFEST), manifest.xmlBytes());
            HandoverDir.restrictFile(workdir.resolve(HandoverDir.MANIFEST));
            PlanFile.write(withOwner(plan, workdir), workdir.resolve(PlanFile.NAME));
        } catch (IOException e) {
            throw new InstallerException(ErrorCode.GENERAL, "cannot prepare the hand-over directory: " + e.getMessage(), e);
        }
        LOG.info("Hand-over directory {} for a {} run ({} step(s)) via {}", workdir, plan.mode(),
                plan.stepIds().size(), strategy.name());
        return new ElevatedRun(strategy, elevation.command(), platform, manifest, plan, fullPlan, listener, token, workdir);
    }

    public Path workdir() {
        return workdir;
    }

    /**
     * Starts the child and waits for {@code ready}: the rights prompt appears now.
     *
     * @throws CancelledException            the user declined the prompt
     * @throws ElevationUnavailableException the strategy could not run
     * @throws InstallerException            the child ended before it was ready
     */
    public void start() {
        token.checkpoint();
        List<String> full = new ArrayList<>(command);
        full.add("--elevated-run=" + workdir);
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
                    drainEvents(null);
                    endedBeforeReady();
                }
                ready = drainEvents(null);
                if (!ready) {
                    pause();
                }
            }
        } catch (RuntimeException e) {
            kill();
            throw e;
        }
        LOG.info("Elevated process {} is ready", process.pid());
    }

    /** The child may run its steps now. */
    public void go() {
        touch(HandoverDir.GO);
    }

    /**
     * Relays events until the child ends and returns what it reported.
     *
     * @param recordSink where the child's record entries go (a partial run); null to ignore them
     */
    public Engine.Result await(Consumer<InstallationRecord.Entry> recordSink) {
        while (process.isAlive()) {
            drainEvents(recordSink);
            pause();
        }
        drainEvents(recordSink);
        int exit = process.exitValue();
        Optional<ElevatedResult> result;
        try {
            result = ElevatedResult.read(workdir.resolve(HandoverDir.RESULT));
        } catch (IOException e) {
            throw new InstallerException(ErrorCode.GENERAL, "unreadable result of the elevated process: "
                    + e.getMessage(), e);
        }
        if (result.isEmpty()) {
            throw endedWithoutResult(exit);
        }
        LOG.info("Elevated process ended with {} (result: {})", exit, result.get().succeeded() ? "ok" : "failed");
        return toResult(result.get());
    }

    private void endedBeforeReady() {
        int exit = process.exitValue();
        Optional<ElevatedResult> result;
        try {
            result = ElevatedResult.read(workdir.resolve(HandoverDir.RESULT));
        } catch (IOException e) {
            result = Optional.empty();
        }
        if (result.isPresent() && result.get().failure().isPresent()) {
            // The child started but could not set itself up (plan, manifest): its own failure.
            throw result.get().failure().get().toException();
        }
        throw endedWithoutResult(exit);
    }

    private InstallerException endedWithoutResult(int exit) {
        String tail = stderr.toString().strip();
        return switch (strategy.classify(exit, tail)) {
            case DECLINED -> {
                LOG.info("Rights prompt declined ({} exit {})", strategy.name(), exit);
                yield new CancelledException("elevation prompt declined (" + strategy.name() + " exit " + exit + ")", null);
            }
            case FAILED -> new ElevationUnavailableException(strategy.name() + " could not elevate (exit " + exit + ")",
                    tail.isEmpty() ? null : tail);
            case NORMAL -> new InstallerException(ErrorCode.GENERAL,
                    "the elevated process ended with exit " + exit + " without reporting a result", null,
                    tail.isEmpty() ? "exit " + exit : tail);
        };
    }

    // --- events --------------------------------------------------------------

    /** Reads what the child appended since last time; returns whether {@code ready} was among it. */
    private boolean drainEvents(Consumer<InstallationRecord.Entry> recordSink) {
        boolean ready = false;
        Path events = workdir.resolve(HandoverDir.EVENTS);
        try (FileChannel channel = FileChannel.open(events, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size > eventsOffset) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) Math.min(size - eventsOffset, 1 << 20));
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
            if (dispatch(line, recordSink)) {
                ready = true;
            }
        }
        return ready;
    }

    /** @return whether the line was {@code ready} */
    private boolean dispatch(String line, Consumer<InstallationRecord.Entry> recordSink) {
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
                case EventLine.STEP_STARTED -> step(f.get(1)).ifPresent(s ->
                        listener.stepStarted(s, Integer.parseInt(f.get(2)), Integer.parseInt(f.get(3))));
                case EventLine.STEP_PROGRESS -> listener.stepProgress(Double.parseDouble(f.get(1)),
                        "1".equals(f.get(3)) ? f.get(2) : null);
                case EventLine.STEP_FINISHED -> step(f.get(1)).ifPresent(s ->
                        listener.stepFinished(s, StepOutcome.valueOf(f.get(2))));
                case EventLine.OVERALL -> listener.overall(Double.parseDouble(f.get(1)));
                case EventLine.OUTPUT -> listener.output(f.get(1));
                case EventLine.ROLLBACK_STARTED -> listener.rollbackStarted(Integer.parseInt(f.get(1)));
                case EventLine.ROLLBACK_PROGRESS -> listener.rollbackProgress(Integer.parseInt(f.get(1)),
                        Integer.parseInt(f.get(2)), f.get(3));
                case EventLine.ROLLBACK_FINISHED -> listener.rollbackFinished(new Rollback.Report(
                        Integer.parseInt(f.get(1)), Integer.parseInt(f.get(2)), EventLine.unlist(f.get(3)),
                        EventLine.unlist(f.get(4)), EventLine.unlist(f.get(5)).stream().map(Path::of).toList()));
                case EventLine.RECORD -> {
                    if (recordSink != null) {
                        EventLine.decodeRecord(f).ifPresent(recordSink);
                    }
                }
                default -> LOG.debug("Unknown event {}", f.get(0));
            }
        } catch (RuntimeException e) {
            LOG.warn("Malformed event line ignored: {} ({})", line, e.toString());
        }
        return false;
    }

    private Optional<InstallStep> step(String id) {
        return fullPlan.steps().stream().filter(p -> p.id().equals(id)).map(PlannedStep::definition).findFirst();
    }

    private Engine.Result toResult(ElevatedResult remote) {
        Optional<InstallerException> failure = remote.failure().map(ElevatedResult.Failure::toException);
        List<Engine.StepReport> reports = new ArrayList<>();
        for (ElevatedResult.Report r : remote.reports()) {
            Optional<InstallStep> step = step(r.stepId());
            if (step.isEmpty()) {
                continue;
            }
            Optional<InstallerException> own = r.outcome() == StepOutcome.FAILED
                    || r.outcome() == StepOutcome.FAILED_CONTINUED
                    ? failure.filter(e -> e instanceof de.yawi.installer.core.error.StepFailedException s
                            && s.stepId().equals(r.stepId()))
                    : Optional.empty();
            reports.add(new Engine.StepReport(step.get(), r.outcome(), own));
        }
        Engine.Result result = new Engine.Result(remote.succeeded(), reports, failure);
        return remote.rollback().map(result::withRollback).orElse(result);
    }

    // --- process plumbing ------------------------------------------------------

    private void sendCancel() {
        if (!cancelSent) {
            cancelSent = true;
            touch(HandoverDir.CANCEL);
            LOG.info("Cancel marker written for the elevated process");
        }
    }

    private void touch(String name) {
        try {
            Files.writeString(workdir.resolve(name), Instant.now().toString());
        } catch (IOException e) {
            LOG.warn("Cannot write marker {}: {}", name, e.toString());
        }
    }

    /** A pause of the poll loop; an interrupt (the wizard's cancel) becomes the cancel marker, the wait goes on. */
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
        }, "elevated-stderr");
        reader.setDaemon(true);
        reader.start();
    }

    private void kill() {
        if (process != null && process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /** Deletes the hand-over directory; the child's log is kept next to the installer's own. */
    @Override
    public void close() {
        if (cancelHook != null) {
            cancelHook.run();
        }
        if (process != null && process.isAlive()) {
            // Closed while the child still waits or works (the normal steps failed): tell it, give it a moment.
            sendCancel();
            try {
                process.waitFor(CLOSE_GRACE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        kill();
        Path log = workdir.resolve(HandoverDir.LOG);
        try {
            if (Files.isRegularFile(log) && Files.size(log) > 0) {
                Path copy = LogSetup.currentLogFile().resolveSibling(COPIED_LOG_NAME);
                Files.copy(log, copy, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Log of the elevated process kept at {}", copy);
            }
        } catch (IOException e) {
            LOG.debug("Could not keep the elevated log: {}", e.toString());
        }
        HandoverDir.delete(workdir);
    }

    // --- building the plan -------------------------------------------------------

    /**
     * The plan for the child, from what the parent resolved.
     *
     * @param resolved         the sources as the download phase left them
     * @param chownDestination whether the destination is the user's (a partial run)
     */
    public static ElevationPlan plan(ElevationNeed need, InstallManifest manifest, Platform platform, Path destination,
                                     Instant startedAt, Set<String> selected, Map<String, String> inputs,
                                     Map<String, SourceResolver.Resolved> resolved, boolean chownDestination) {
        Map<String, ElevationPlan.Artifact> artifacts = new LinkedHashMap<>();
        resolved.forEach((id, r) -> {
            if (r.file().isPresent()) {
                artifacts.put(id, ElevationPlan.Artifact.file(r.kind(), r.file().get()));
            } else if (r.provider() instanceof Provider.Bundled b) {
                artifacts.put(id, ResourceRef.isClasspath(b.path())
                        ? ElevationPlan.Artifact.classpath(b.path())
                        : ElevationPlan.Artifact.file(ProviderKind.BUNDLED, Path.of(b.path())));
            }
        });
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : List.of("HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "XDG_DATA_HOME", "XDG_CONFIG_HOME",
                "XDG_STATE_HOME", "PATH", "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "ProgramData",
                "SystemRoot")) {
            platform.environment().env(name).ifPresent(v -> env.put(name, v));
        }
        Map<String, String> properties = new LinkedHashMap<>();
        for (String name : List.of("user.home", "java.io.tmpdir")) {
            platform.environment().property(name).ifPresent(v -> properties.put(name, v));
        }
        return new ElevationPlan(need.mode(), destination, startedAt, selected, inputs, need.elevatedStepIds(),
                artifacts, manifest.origin(), env, properties, Optional.empty(), Optional.empty(), chownDestination);
    }

    /** The owner of the hand-over directory is the calling user; the child gives created files back to them. */
    private static ElevationPlan withOwner(ElevationPlan plan, Path workdir) {
        if (!HandoverDir.posix()) {
            return plan;
        }
        try {
            PosixFileAttributes attributes = Files.readAttributes(workdir, PosixFileAttributes.class);
            return new ElevationPlan(plan.mode(), plan.destination(), plan.startedAt(), plan.selected(), plan.inputs(),
                    plan.stepIds(), plan.artifacts(), plan.origin(), plan.env(), plan.properties(),
                    Optional.of(attributes.owner().getName()), Optional.of(attributes.group().getName()),
                    plan.chownDestination());
        } catch (IOException | UnsupportedOperationException e) {
            LOG.warn("Cannot determine the owner of {}: {}", workdir, e.toString());
            return plan;
        }
    }
}
