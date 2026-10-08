package de.yawi.installer.core.elevation;

import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.download.ResolvedArtifacts;
import de.yawi.installer.core.download.SourceResolver;
import de.yawi.installer.core.engine.Backup;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.Engine;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.ExecutionPlan;
import de.yawi.installer.core.engine.FailurePrompt;
import de.yawi.installer.core.engine.InstallRunner;
import de.yawi.installer.core.engine.Placeholders;
import de.yawi.installer.core.engine.RecordedRun;
import de.yawi.installer.core.engine.Rollback;
import de.yawi.installer.core.engine.Uninstaller;
import de.yawi.installer.core.engine.step.Steps;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestOrigin;
import de.yawi.installer.core.manifest.ManifestParser;
import de.yawi.installer.core.manifest.Provider;
import de.yawi.installer.core.manifest.Source;
import de.yawi.installer.core.platform.Environment;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.RecordReader;
import de.yawi.installer.core.state.RecordWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The elevated process: started by {@link ElevatedRun} as
 * {@code --elevated-run=<dir>}, it reads the plan, says {@code ready}, waits
 * for {@code go} (or {@code cancel}), runs exactly the steps the plan names
 * through the ordinary {@link Engine}, reports through {@code events.log}
 * and {@code result.xml}, and hands created files back to the user. It
 * downloads nothing, registers nothing and shows no window; a step's
 * {@code onFailure="ask"} means abort here, there is nobody to ask.
 */
public final class ElevatedWorker {

    private static final Logger LOG = LoggerFactory.getLogger(ElevatedWorker.class);

    static final Duration POLL = Duration.ofMillis(200);
    /** Backup folder suffix of a partial run, so it never touches the parent's. */
    static final String BACKUP_SUFFIX = "elevated";

    private static volatile boolean child;

    private ElevatedWorker() {
    }

    /**
     * Whether this process is the elevated child: the {@code elevated} steps
     * and their reverse commands are its to run, whatever rights the strategy
     * actually gave it ({@code direct} gives none).
     */
    public static boolean isChild() {
        return child;
    }

    /** @return the process exit code */
    public static int run(Path workdir) {
        child = true;
        Path dir = workdir.toAbsolutePath().normalize();
        Path resultFile = dir.resolve(HandoverDir.RESULT);
        ElevatedResult result;
        try {
            LogSetup.setConsoleLevel("WARN");
            LogSetup.redirectToFile(dir.resolve(HandoverDir.LOG));
            result = execute(dir);
        } catch (InstallerException e) {
            LOG.error("Elevated run ended before its steps [{}]: {}", e.code(), e.getMessage());
            result = ElevatedResult.failed(e);
        } catch (RuntimeException e) {
            LOG.error("Elevated run failed unexpectedly", e);
            result = ElevatedResult.failed(InstallerException.wrap(e));
        }
        try {
            result.write(resultFile);
        } catch (IOException e) {
            LOG.error("Cannot write {}: {}", resultFile, e.toString());
            return ErrorCode.GENERAL.exitCode();
        }
        return result.exitCode();
    }

    /**
     * The elevated uninstall: started as {@code --elevated-uninstall=<dir>},
     * removes an installation whose files sit where the caller cannot write.
     *
     * @return the process exit code
     */
    public static int runUninstall(Path workdir) {
        child = true;
        Path dir = workdir.toAbsolutePath().normalize();
        Path resultFile = dir.resolve(HandoverDir.RESULT);
        UninstallResult result;
        try {
            LogSetup.setConsoleLevel("WARN");
            LogSetup.redirectToFile(dir.resolve(HandoverDir.LOG));
            result = executeUninstall(dir);
        } catch (InstallerException e) {
            LOG.error("Elevated uninstall ended before it ran [{}]: {}", e.code(), e.getMessage());
            result = UninstallResult.failed(e);
        } catch (RuntimeException e) {
            LOG.error("Elevated uninstall failed unexpectedly", e);
            result = UninstallResult.failed(InstallerException.wrap(e));
        }
        try {
            result.write(resultFile);
        } catch (IOException e) {
            LOG.error("Cannot write {}: {}", resultFile, e.toString());
            return ErrorCode.GENERAL.exitCode();
        }
        return result.exitCode();
    }

    private static UninstallResult executeUninstall(Path dir) {
        UninstallPlan plan = readUninstallPlan(dir);
        Platform platform = platformFor(plan.env(), plan.properties());
        InstallManifest manifest = manifest(dir, plan.origin());
        InstallationRecord record;
        try {
            record = RecordReader.read(RecordWriter.defaultFile(plan.destination()));
        } catch (IOException e) {
            throw new InstallerException(ErrorCode.GENERAL, "cannot read the installation record under "
                    + plan.destination() + ": " + e.getMessage(), e);
        }
        LOG.info("Elevated uninstall of {} {} from {} (deleteModified={}, deleteUnrecorded={}, elevated={})",
                manifest.product().id(), record.productVersion(), plan.destination(), plan.deleteModified(),
                plan.deleteUnrecorded(), platform.isElevated());
        CancellationToken token = new CancellationToken();
        try (EventSink sink = new EventSink(dir.resolve(HandoverDir.EVENTS))) {
            sink.ready();
            Thread watcher = watchCancel(dir, token);
            try {
                if (!awaitGo(dir, token)) {
                    LOG.info("Cancelled before the uninstall started");
                    return UninstallResult.ofReport(new Uninstaller.Report(0, 0, java.util.List.of(), java.util.List.of(),
                            java.util.List.of(), java.util.List.of(), true, false));
                }
                // The child never touches the register (the caller's profile); the unelevated parent does.
                Uninstaller uninstaller = new Uninstaller(manifest, platform, record, Optional.empty());
                Uninstaller.Report report = uninstaller.run(
                        new Uninstaller.Options(plan.deleteModified(), plan.deleteUnrecorded()), sink, token);
                return UninstallResult.ofReport(report);
            } finally {
                watcher.interrupt();
            }
        } catch (IOException e) {
            throw new InstallerException(ErrorCode.GENERAL, "cannot write the event file: " + e.getMessage(), e);
        }
    }

    private static UninstallPlan readUninstallPlan(Path dir) {
        Path file = dir.resolve(UninstallPlan.NAME);
        try {
            UninstallPlan plan = UninstallPlan.read(file);
            Files.deleteIfExists(file);
            return plan;
        } catch (IOException e) {
            throw new InstallerException(ErrorCode.GENERAL, "cannot read the uninstall plan: " + e.getMessage(), e);
        }
    }

    private static ElevatedResult execute(Path dir) {
        ElevationPlan plan = readPlan(dir);
        Platform platform = platform(plan);
        InstallManifest manifest = manifest(dir, plan);
        LOG.info("Elevated {} run of {} {} into {}: {} step(s), elevated={}", plan.mode(), manifest.product().id(),
                manifest.product().version(), plan.destination(), plan.stepIds().size(), platform.isElevated());

        CancellationToken token = new CancellationToken();
        try (EventSink sink = new EventSink(dir.resolve(HandoverDir.EVENTS))) {
            Placeholders placeholders = Placeholders.of(manifest, platform, plan.destination(), plan.inputs(),
                    plan.selected());
            ExecutionPlan full = ExecutionPlan.build(manifest, plan.selected(), platform, placeholders, Steps.DEFAULT);
            ExecutionPlan segment = full.subset(plan.stepIds());
            InstallationRecord record = new InstallationRecord(manifest.product().id(), manifest.product().version(),
                    plan.startedAt(), plan.destination(), plan.selected(),
                    InstallationRecord.withoutSecrets(plan.inputs(), InstallRunner.inputLabels(manifest)));
            ExecutionContext context = new ExecutionContext(manifest, platform, plan.destination(), plan.inputs(),
                    plan.selected(), placeholders, artifacts(manifest, platform, plan), record, sink,
                    FailurePrompt.ABORT_ALWAYS, token);
            segment.dryRun(context).forEach(line -> LOG.info("  {}", line));

            sink.ready();
            Thread watcher = watchCancel(dir, token);
            try {
                if (!awaitGo(dir, token)) {
                    LOG.info("Cancelled before the first step");
                    return ElevatedResult.of(new Engine.Result(false, java.util.List.of(),
                            Optional.of(new CancelledException())));
                }
                Engine.Result result = plan.mode() == ElevationNeed.Mode.WHOLE
                        ? RecordedRun.execute(segment, context, sink)
                        : partial(segment, context, record, sink);
                OwnershipPass.apply(plan, record, platform, Set.of(Backup.of(context, BACKUP_SUFFIX).root()));
                return ElevatedResult.of(result);
            } finally {
                watcher.interrupt();
            }
        } catch (IOException e) {
            throw new InstallerException(ErrorCode.GENERAL, "cannot write the event file: " + e.getMessage(), e);
        }
    }

    /** The partial segment: entries go to the parent's record, a failure undoes only this segment. */
    private static Engine.Result partial(ExecutionPlan segment, ExecutionContext context, InstallationRecord record,
                                         EventSink sink) {
        record.onEntry(sink::record);
        Engine.Result result = new Engine().run(segment, context);
        if (result.succeeded()) {
            return result;
        }
        Backup backup = Backup.of(context, BACKUP_SUFFIX);
        Rollback rollback = new Rollback(context.forRollback(sink, new CancellationToken()), segment, backup,
                Optional.empty(), false);
        return result.withRollback(rollback.run());
    }

    // --- set-up ------------------------------------------------------------

    private static ElevationPlan readPlan(Path dir) {
        Path file = dir.resolve(PlanFile.NAME);
        try {
            ElevationPlan plan = PlanFile.read(file);
            Files.deleteIfExists(file); // secrets inside; the parent deletes the folder anyway
            return plan;
        } catch (IOException e) {
            throw new InstallerException(ErrorCode.GENERAL, "cannot read the elevation plan: " + e.getMessage(), e);
        }
    }

    /** This machine's platform, but with the calling user's locations (home, profile, temp). */
    static Platform platform(ElevationPlan plan) {
        return platformFor(plan.env(), plan.properties());
    }

    static Platform platformFor(Map<String, String> planEnv, Map<String, String> planProperties) {
        Map<String, String> env = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        env.putAll(planEnv);
        Map<String, String> properties = new LinkedHashMap<>(planProperties);
        properties.putIfAbsent("java.io.tmpdir", System.getProperty("java.io.tmpdir"));
        properties.putIfAbsent("user.name", System.getProperty("user.name"));
        return PlatformFactory.detect(System.getProperty("os.name"), System.getProperty("os.arch"),
                Environment.of(env, properties));
    }

    private static InstallManifest manifest(Path dir, ElevationPlan plan) {
        return manifest(dir, plan.origin());
    }

    private static InstallManifest manifest(Path dir, ManifestOrigin origin) {
        try {
            byte[] bytes = Files.readAllBytes(dir.resolve(HandoverDir.MANIFEST));
            return new ManifestParser().parse(bytes, origin);
        } catch (IOException e) {
            throw new InstallerException(ErrorCode.MANIFEST_INVALID, "cannot read the handed-over manifest: "
                    + e.getMessage(), e);
        }
    }

    /** The sources as the parent resolved them: a file, or the installer's own bundled copy. */
    static ResolvedArtifacts artifacts(InstallManifest manifest, Platform platform, ElevationPlan plan) {
        Map<String, SourceResolver.Resolved> resolved = new LinkedHashMap<>();
        for (Source source : manifest.sourcesFor(plan.selected(), platform.os())) {
            ElevationPlan.Artifact artifact = plan.artifacts().get(source.id());
            if (artifact == null) {
                continue; // BundledArtifacts fallback of ResolvedArtifacts
            }
            Provider provider = artifact.classpathRef().<Provider>map(Provider.Bundled::new)
                    .orElseGet(() -> providerOfKind(source, artifact.kind()));
            resolved.put(source.id(), new SourceResolver.Resolved(source, artifact.file(), provider));
        }
        return new ResolvedArtifacts(resolved);
    }

    private static Provider providerOfKind(Source source, ProviderKind kind) {
        return source.providers().stream().filter(p -> ProviderKind.of(p) == kind).findFirst()
                .orElseGet(() -> source.providers().isEmpty() ? new Provider.Bundled("classpath:/missing")
                        : source.providers().get(0));
    }

    // --- markers -----------------------------------------------------------

    /** @return true for {@code go}, false for {@code cancel} */
    private static boolean awaitGo(Path dir, CancellationToken token) {
        Path go = dir.resolve(HandoverDir.GO);
        Path cancel = dir.resolve(HandoverDir.CANCEL);
        while (true) {
            if (Files.exists(cancel) || token.isCancelled()) {
                return false;
            }
            if (Files.exists(go)) {
                return true;
            }
            try {
                Thread.sleep(POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static Thread watchCancel(Path dir, CancellationToken token) {
        Path cancel = dir.resolve(HandoverDir.CANCEL);
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (Files.exists(cancel)) {
                    LOG.info("Cancel marker found");
                    token.cancel();
                    return;
                }
                try {
                    Thread.sleep(POLL.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "elevated-cancel-watch");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
