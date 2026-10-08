package de.yawi.installer.core.engine;

import de.yawi.installer.core.download.DownloadListener;
import de.yawi.installer.core.download.DownloadProgress;
import de.yawi.installer.core.download.LocalCache;
import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.download.ResolvedArtifacts;
import de.yawi.installer.core.download.SourceResolver;
import de.yawi.installer.core.elevation.ElevatedRun;
import de.yawi.installer.core.elevation.Elevation;
import de.yawi.installer.core.elevation.ElevationNeed;
import de.yawi.installer.core.elevation.ElevationPlan;
import de.yawi.installer.core.engine.step.Steps;
import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.InputValidator;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.Source;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One installation from start to finish, headless: obtain every
 * needed source (cache, then the providers in the user's preferred order),
 * build the plan, write the record and run the {@link Engine}. The wizard's
 * {@code InstallTask} and the silent mode both run through here; they differ
 * only in the {@link ProgressListener} they pass.
 *
 * <p>Progress: {@link ProgressListener#overall} receives one fraction over
 * the whole run - the download phase takes the first {@link #DOWNLOAD_SHARE}
 * when there is one, the steps the rest.
 */
public final class InstallRunner {

    private static final Logger LOG = LoggerFactory.getLogger(InstallRunner.class);

    /** Share of the overall progress the download phase takes when there is one. */
    public static final double DOWNLOAD_SHARE = 0.3;

    /**
     * What to install where.
     *
     * @param selected the resolved selection (dependencies included)
     * @param inputs   values of the selected components' inputs only
     * @param choices  preferred provider kind per source id
     * @param cache    artifacts fetched by someone else ({@code --cache}), consulted first
     */
    public record Request(InstallManifest manifest, Platform platform, Path destination, Set<String> selected,
                          Map<String, String> inputs, Map<String, ProviderKind> choices, Set<String> associations,
                          boolean pathEntries, Optional<LocalCache> cache) {

        public Request {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(platform, "platform");
            Objects.requireNonNull(destination, "destination");
            selected = Set.copyOf(selected);
            inputs = Map.copyOf(inputs);
            choices = Map.copyOf(choices);
            associations = Set.copyOf(associations);
            cache = cache == null ? Optional.empty() : cache;
        }

        /** The same request with the PATH entries turned on or off (E13-S03). */
        public Request withPathEntries(boolean value) {
            return new Request(manifest, platform, destination, selected, inputs, choices, associations, value, cache);
        }

        /**
         * Resolves a raw selection and raw input values: dependencies are
         * added, inputs of unselected components dropped, missing ones
         * default; sources without an explicit choice get
         * {@link SourceResolver#defaultKind}.
         */
        public static Request of(InstallManifest manifest, Platform platform, Path destination,
                                 Collection<String> selectedIds, Map<String, String> values,
                                 Map<String, ProviderKind> choices, Optional<LocalCache> cache) {
            Set<String> selected = manifest.resolveSelection(selectedIds);
            Map<String, ProviderKind> chosen = new LinkedHashMap<>();
            manifest.sourcesFor(selected, platform.os()).forEach(s -> chosen.put(s.id(),
                    choices.getOrDefault(s.id(), SourceResolver.defaultKind(s))));
            // No explicit choice: the associations the manifest turns on by default (E13-S02) and,
            // if it declares any, the PATH entries (E13-S03).
            Set<String> associations = Set.copyOf(manifest.integration().defaultAssociationExtensions());
            boolean pathEntries = !manifest.integration().pathEntries().isEmpty();
            return new Request(manifest, platform, destination, selected, selectedInputs(manifest, selected, values),
                    chosen, associations, pathEntries, cache);
        }

        /**
         * As {@link #of}, but with an explicit set of file-association extensions to create (E13-S02: a
         * silent run or the wizard passing the user's choice); the rest defaults as in {@code of}.
         */
        public static Request of(InstallManifest manifest, Platform platform, Path destination,
                                 Collection<String> selectedIds, Map<String, String> values,
                                 Map<String, ProviderKind> choices, Collection<String> associations,
                                 Optional<LocalCache> cache) {
            Request base = of(manifest, platform, destination, selectedIds, values, choices, cache);
            return new Request(base.manifest(), base.platform(), base.destination(), base.selected(), base.inputs(),
                    base.choices(), Set.copyOf(associations), base.pathEntries(), base.cache());
        }

        /** The sources that will be downloaded (not bundled, not cached - the cache is only known at run time). */
        public List<Source> downloads() {
            return manifest.sourcesFor(selected, platform.os()).stream()
                    .filter(s -> choices.get(s.id()) != ProviderKind.BUNDLED).toList();
        }

        /** The record this run writes, before any entry. */
        public InstallationRecord newRecord(Instant startedAt) {
            return new InstallationRecord(manifest.product().id(), manifest.product().version(), startedAt,
                    destination, selected, InstallationRecord.withoutSecrets(inputs, inputLabels(manifest)));
        }

        public Placeholders placeholders() {
            return Placeholders.of(manifest, platform, destination, inputs, selected);
        }
    }

    private final SourceResolver resolver;
    private final Optional<InstallationRegistry> registry;
    private final Elevation elevation;

    public InstallRunner(SourceResolver resolver) {
        this(resolver, Optional.empty());
    }

    /**
     * @param registry where a successful run registers the installation
     *                 (E14-S01-T03); empty for runs that must leave no trace
     *                 outside the destination (tests)
     */
    public InstallRunner(SourceResolver resolver, Optional<InstallationRegistry> registry) {
        this(resolver, registry, Elevation.forRuntime());
    }

    /** @param elevation how administrator rights are obtained when the run needs them (E12) */
    public InstallRunner(SourceResolver resolver, Optional<InstallationRegistry> registry, Elevation elevation) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.elevation = Objects.requireNonNull(elevation, "elevation");
    }

    /**
     * The plan as text, without touching anything ({@code --dry-run}): the
     * artifacts are described by their bundled or first provider.
     *
     * @throws de.yawi.installer.core.manifest.ManifestException if a placeholder cannot be resolved
     */
    public List<String> dryRun(Request request) {
        Placeholders placeholders = request.placeholders();
        ExecutionContext context = new ExecutionContext(request.manifest(), request.platform(), request.destination(),
                request.inputs(), request.selected(), placeholders, new BundledArtifacts(),
                request.newRecord(Instant.now()), ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS,
                new CancellationToken());
        return ExecutionPlan.build(request.manifest(), request.selected(), request.platform(), placeholders,
                Steps.DEFAULT, request.associations(), request.pathEntries()).dryRun(context);
    }

    /**
     * Runs the installation. Never throws for a failed installation - that
     * is in the {@link Engine.Result}; it does throw for anything that
     * prevents the run from starting (a source that cannot be obtained, a
     * record that cannot be opened, cancellation during the download phase).
     */
    public Engine.Result run(Request request, ProgressListener listener, FailurePrompt prompt,
                             CancellationToken token) {
        Objects.requireNonNull(request, "request");
        ProgressListener out = listener == null ? ProgressListener.NOOP : listener;
        InstallManifest manifest = request.manifest();
        Platform platform = request.platform();
        Instant startedAt = Instant.now();

        InstallationRecord record = request.newRecord(startedAt);
        Placeholders placeholders = request.placeholders();

        // Phase 1: obtain every needed source (downloads first 30 % of the bar, if any).
        List<Source> downloads = request.downloads();
        double share = downloads.isEmpty() ? 0 : DOWNLOAD_SHARE;
        Set<String> fromCache = new HashSet<>();
        Map<String, SourceResolver.Resolved> resolved = fetchSources(request, downloads, share, out, token, fromCache);

        ExecutionPlan plan = ExecutionPlan.build(manifest, request.selected(), platform, placeholders, Steps.DEFAULT,
                request.associations(), request.pathEntries());
        ElevationNeed need = elevation.assess(plan, request.destination(), platform);
        LOG.info("Installing {} {} to {} - {} step(s), elevation {}", manifest.product().id(),
                manifest.product().version(), request.destination(), plan.steps().size(), need.mode());

        // Phase 2 and 3: the steps, and the rollback if they fail. The part that needs administrator rights runs
        // in a second, elevated process (E12-S03): everything when the destination needs them, else the
        // elevated="true" steps as one block after the others.
        Engine.Result result = switch (need.mode()) {
            case NONE -> {
                ExecutionContext context = context(request, placeholders, resolved, record,
                        SegmentListener.fractionOnly(out, share, 1 - share), prompt, token);
                plan.dryRun(context).forEach(line -> LOG.info("  {}", line));
                yield RecordedRun.execute(plan, context, out);
            }
            case WHOLE -> {
                ExecutionContext context = context(request, placeholders, resolved, record,
                        SegmentListener.fractionOnly(out, share, 1 - share), prompt, token);
                plan.dryRun(context).forEach(line -> LOG.info("  {}", line));
                ElevationPlan handover = ElevatedRun.plan(need, manifest, platform, request.destination(), startedAt,
                        request.selected(), request.inputs(), resolved, false);
                try (ElevatedRun child = ElevatedRun.prepare(elevation, platform, manifest, handover, plan,
                        context.listener(), token)) {
                    child.start();
                    child.go();
                    yield child.await(null);
                }
            }
            case PARTIAL -> partial(request, placeholders, resolved, record, plan, need, out, share, prompt, token);
        };
        if (result.succeeded()) {
            deleteDownloads(resolved, fromCache);
            registerQuietly(record);
        }
        return result;
    }

    private static ExecutionContext context(Request request, Placeholders placeholders,
                                            Map<String, SourceResolver.Resolved> resolved, InstallationRecord record,
                                            ProgressListener listener, FailurePrompt prompt, CancellationToken token) {
        return new ExecutionContext(request.manifest(), request.platform(), request.destination(), request.inputs(),
                request.selected(), placeholders, new ResolvedArtifacts(resolved), record, listener, prompt, token);
    }

    /**
     * The normal steps here, the elevated block in the child: the child is
     * started first so the rights prompt appears at the start, runs after the
     * normal steps succeeded, and appends its record entries to this run's
     * record file. Progress: both segments placed into the part after the
     * downloads by their weight.
     */
    private Engine.Result partial(Request request, Placeholders placeholders,
                                  Map<String, SourceResolver.Resolved> resolved, InstallationRecord record,
                                  ExecutionPlan plan, ElevationNeed need, ProgressListener out, double downloadShare,
                                  FailurePrompt prompt, CancellationToken token) {
        Set<String> elevatedIds = new HashSet<>(need.elevatedStepIds());
        List<String> normalIds = plan.ids().stream().filter(id -> !elevatedIds.contains(id)).toList();
        double elevatedWeight = plan.steps().stream().filter(p -> elevatedIds.contains(p.id()))
                .mapToDouble(PlannedStep::weight).sum();
        double stepsShare = 1 - downloadShare;
        double normalShare = stepsShare * (1 - elevatedWeight);
        int total = plan.steps().size();

        ExecutionPlan normal = plan.subset(normalIds);
        ProgressListener normalListener = new SegmentListener(out, 0, total, downloadShare, normalShare);
        ProgressListener elevatedListener = new SegmentListener(out, normalIds.size(), total,
                downloadShare + normalShare, stepsShare - normalShare);
        ExecutionContext context = context(request, placeholders, resolved, record, normalListener, prompt, token);
        plan.dryRun(context).forEach(line -> LOG.info("  {}", line));

        ElevationPlan handover = ElevatedRun.plan(need, request.manifest(), request.platform(), request.destination(),
                record.startedAt(), request.selected(), request.inputs(), resolved, true);
        try (ElevatedRun child = ElevatedRun.prepare(elevation, request.platform(), request.manifest(), handover, plan,
                elevatedListener, token)) {
            child.start();
            return RecordedRun.execute(normal, context, out, writer -> {
                child.go();
                return child.await(writer::append);
            });
        }
    }

    /** The register is a convenience for the next start, never a reason to fail a finished installation. */
    private void registerQuietly(InstallationRecord record) {
        registry.ifPresent(r -> {
            try {
                r.register(record);
                LOG.info("Registered the installation of {} under {} in {}", record.productId(), record.destination(),
                        r.directory());
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not register the installation in {}: {}", r.directory(), e.toString());
            }
        });
    }

    /**
     * Resolves every needed source: from the cache if it has it, else in the
     * user's preferred way. Only sources that are not taken from the shipped
     * copy count as downloads for the progress; bundled and cached ones
     * resolve silently apart from their checksum pass.
     */
    private Map<String, SourceResolver.Resolved> fetchSources(Request request, List<Source> downloads, double share,
                                                             ProgressListener listener, CancellationToken token,
                                                             Set<String> fromCache) {
        Path downloadDir = ExecutionContext.workDir(request.platform()).resolve("downloads");
        long totalBytes = downloads.stream().mapToLong(s -> Math.max(s.sizeBytes(), 0)).sum();
        Map<String, SourceResolver.Resolved> resolved = new LinkedHashMap<>();
        long doneBytes = 0;
        int index = 0;
        for (Source source : request.manifest().sourcesFor(request.selected(), request.platform().os())) {
            token.checkpoint();
            ProviderKind kind = request.choices().get(source.id());
            boolean download = kind != ProviderKind.BUNDLED;
            if (download) {
                listener.downloadStarted(source, index, downloads.size());
            }
            long offset = doneBytes;
            DownloadListener progress = new DownloadListener() {
                @Override
                public void progress(DownloadProgress p) {
                    listener.downloadProgress(source, p);
                    if (totalBytes > 0) {
                        listener.overall(share * (offset + Math.min(p.bytes(), Math.max(source.sizeBytes(), 0)))
                                / totalBytes);
                    }
                }

                @Override
                public void retry(java.net.URI url, int attempt, Throwable cause) {
                    listener.output("retry " + attempt + " for " + url + ": " + cause.getMessage());
                }

                @Override
                public void verifying(String fileName, long total) {
                    // Bundled or cached data with a checksum: shown like a download, but not counted as one.
                    listener.downloadVerifying(source);
                }

                @Override
                public void fallback(ProviderKind from, ProviderKind to, String reason) {
                    listener.downloadFallback(source, from, to, reason);
                }
            };
            Optional<Path> cached = lookupCache(request, source, progress, token);
            if (cached.isPresent()) {
                LOG.info("Source {}: using cached {}", source.id(), cached.get());
                listener.output("source " + source.id() + ": from cache " + cached.get().getFileName());
                resolved.put(source.id(), new SourceResolver.Resolved(source, cached, source.providers().get(0)));
                fromCache.add(source.id());
            } else {
                resolved.put(source.id(), resolver.resolve(source, kind, downloadDir, progress, token));
            }
            listener.downloadFinished(source);
            if (download) {
                doneBytes += Math.max(source.sizeBytes(), 0);
                index++;
            }
        }
        if (!downloads.isEmpty()) {
            listener.overall(share);
        }
        return resolved;
    }

    private static Optional<Path> lookupCache(Request request, Source source, DownloadListener progress,
                                              CancellationToken token) {
        if (request.cache().isEmpty()) {
            return Optional.empty();
        }
        try {
            return request.cache().get().lookup(source, progress, token);
        } catch (IOException e) {
            LOG.warn("Source {}: cache lookup failed, obtaining it the usual way: {}", source.id(), e.toString());
            return Optional.empty();
        }
    }

    /** Downloaded files are only needed once; after a successful installation they go. Cache files stay. */
    private static void deleteDownloads(Map<String, SourceResolver.Resolved> resolved, Set<String> fromCache) {
        resolved.forEach((id, r) -> {
            if (fromCache.contains(id)) {
                return;
            }
            r.file().ifPresent(file -> {
                try {
                    Files.deleteIfExists(file);
                    Path dir = file.getParent();
                    if (dir != null) {
                        Files.deleteIfExists(dir); // only if empty
                    }
                } catch (IOException e) {
                    LOG.debug("Could not delete download {}: {}", file, e.toString());
                }
            });
        });
    }

    /** The values of the selected components' inputs; defaults for anything the caller never gave. */
    public static Map<String, String> selectedInputs(InstallManifest manifest, Set<String> selected,
                                                     Map<String, String> values) {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (Component c : manifest.components()) {
            if (selected.contains(c.id())) {
                c.inputs().forEach(i -> inputs.put(i.id(), values.getOrDefault(i.id(), InputValidator.defaultValue(i))));
            }
        }
        return inputs;
    }

    /** The label of every input, for {@link InstallationRecord#withoutSecrets}. */
    public static Map<String, String> inputLabels(InstallManifest manifest) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Component c : manifest.components()) {
            c.inputs().forEach(i -> labels.put(i.id(), i.label() == null ? "" : i.label()));
        }
        return labels;
    }
}
