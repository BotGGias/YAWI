package de.yawi.installer.cli;

import de.yawi.installer.core.answers.AnswerFile;
import de.yawi.installer.core.answers.AnswerFileReader;
import de.yawi.installer.core.download.LocalCache;
import de.yawi.installer.core.download.SourceResolver;
import de.yawi.installer.core.download.Version;
import de.yawi.installer.core.download.torrent.TorrentRuntime;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.Engine;
import de.yawi.installer.core.engine.FailurePrompt;
import de.yawi.installer.core.engine.InstallRunner;
import de.yawi.installer.core.engine.LaunchTarget;
import de.yawi.installer.core.engine.Rollback;
import de.yawi.installer.core.engine.Uninstaller;
import de.yawi.installer.core.error.AnswerFileException;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.InsufficientSpaceException;
import de.yawi.installer.core.error.InvalidArgumentsException;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.error.NoInstallationException;
import de.yawi.installer.core.error.PermissionDeniedException;
import de.yawi.installer.core.error.UninstallFailedException;
import de.yawi.installer.core.i18n.ManifestMessages;
import de.yawi.installer.core.i18n.Messages;
import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.ComponentInput;
import de.yawi.installer.core.manifest.ComponentSelection;
import de.yawi.installer.core.manifest.InputValidator;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestLocator;
import de.yawi.installer.core.platform.AppLauncher;
import de.yawi.installer.core.platform.DestinationValidator;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRegistry;
import de.yawi.installer.core.state.RecordReader;
import de.yawi.installer.core.state.RecordSelection;
import de.yawi.installer.core.state.RecordWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The installation without a window: everything the wizard
 * asks is answered by the arguments, the answer file ({@code --config},
 * , the manifest's defaults or - with {@code --update} - the record
 * of the existing installation. Ends with the exit code of
 * {@link ErrorCode}, 0 on success, and, if asked, a result file and the
 * relaunch of the installed application (update contract).
 *
 * <p>Order: platform, answer file, manifest, log, licence, destination,
 * selection and inputs, cache, <em>then</em> the wait for the caller's
 * processes, then the first write. So a contract violation is reported
 * before anything is touched and without keeping the caller waiting.
 */
public final class SilentRun {

    private static final Logger LOG = LoggerFactory.getLogger(SilentRun.class);

    private final Arguments args;
    private final PrintStream out;
    private final PrintStream err;
    private final SourceResolver resolver;
    private final Function<String, InstallManifest> locator;
    private final Function<String, InstallationRegistry> registries;
    private final Supplier<Platform> platforms;

    public SilentRun(Arguments args, PrintStream out, PrintStream err) {
        this(args, out, err, SourceResolver.forRuntime(), explicit -> ManifestLocator.forRuntime().locate(explicit),
                productId -> InstallationRegistry.forPlatform(PlatformFactory.current(), productId),
                PlatformFactory::current);
    }

    /**
     * @param locator    loads the manifest for the {@code --manifest} value (null = default lookup); tests inject one
     * @param registries the register a successful run writes to, by product id (known only once the manifest is
     *                   loaded); tests point it at a scratch directory
     * @param platforms  the platform to run on; tests give one whose home is a scratch directory, so shortcuts
     *                   never land in the real menu
     */
    public SilentRun(Arguments args, PrintStream out, PrintStream err, SourceResolver resolver,
                     Function<String, InstallManifest> locator, Function<String, InstallationRegistry> registries,
                     Supplier<Platform> platforms) {
        this.args = Objects.requireNonNull(args, "args");
        this.out = out;
        this.err = err;
        this.resolver = resolver;
        this.locator = locator;
        this.registries = Objects.requireNonNull(registries, "registries");
        this.platforms = Objects.requireNonNull(platforms, "platforms");
    }

    /** Runs to the end and returns the process exit status; never throws. */
    public int run() {
        // stdout carries the mode's own lines (help, version, progress); the file gets the full log.
        LogSetup.setConsoleLevel("WARN");
        Messages messages = Messages.load();
        Locale locale = args.lang() == null ? Locale.getDefault() : Locale.forLanguageTag(args.lang());
        if (args.help()) {
            printHelp(messages, locale);
            return 0;
        }
        boolean realRun = args.silent() && !args.version();

        Platform platform = null;
        InstallManifest manifest = null;
        Path destination = null;
        int code;
        String message;
        try {
            platform = platforms.get();
            LOG.info("Silent installer on {} {} ({}) - detected {}; arguments {}", System.getProperty("os.name"),
                    System.getProperty("os.version"), System.getProperty("os.arch"), platform, args);
            // The file first: its language already applies to every message from here on.
            Optional<AnswerFile> file = args.config() == null ? Optional.empty()
                    : Optional.of(AnswerFileReader.read(Path.of(args.config())));
            Answers answers = Answers.merge(args, file);
            manifest = locator.apply(args.manifest());
            locale = manifest.languages().resolve(answers.lang().map(Locale::forLanguageTag).orElse(locale));
            if (file.isPresent()) {
                AnswerFileReader.check(file.get(), manifest, Path.of(args.config()));
            }
            if (args.version()) {
                out.println("yawi-installer " + manifest.product().id() + " " + manifest.product().version());
                // The installer's own build, distinct from the product version above.
                out.println("installer build " + de.yawi.installer.BuildInfo.embedded().summary());
                return 0;
            }
            if (args.log() != null) {
                LogSetup.redirectToFile(Path.of(args.log()));
            } else {
                LogSetup.redirect(platform.logDir(manifest.product().id()));
            }
            ConsoleReporter reporter = new ConsoleReporter(out, mode(), messages, new ManifestMessages(manifest), locale);

            if (args.uninstall()) {
                destination = destination(manifest, platform, answers, messages, locale);
                return uninstall(manifest, platform, destination, messages, locale, reporter, realRun);
            }
            // A repair works on an existing installation whose record implies the earlier acceptance (like the wizard).
            if (manifest.wizard().licenseOrEmpty().isPresent() && !answers.licenseAccepted() && !args.repair()) {
                if (file.isPresent()) {
                    throw new AnswerFileException(Path.of(args.config()), "<license accepted=\"true\"/> is missing: "
                            + "the manifest has a license that must be accepted (or pass --accept-license)");
                }
                throw new InvalidArgumentsException("--accept-license", "the manifest has a license that must be accepted");
            }
            destination = destination(manifest, platform, answers, messages, locale);
            Selection selection = args.update() || args.repair()
                    ? fromRecord(manifest, destination, args.repair())
                    : fromAnswers(manifest, platform, answers);
            Map<String, String> inputs = validatedInputs(manifest, selection, answers, messages, locale);
            Optional<LocalCache> cache = openCache();
            // File associations: --no-associations disables them, else the answer file's choice, else the manifest default (E13-S02).
            Optional<Boolean> associate = args.noAssociations() ? Optional.of(false)
                    : file.flatMap(AnswerFile::associateFileTypes);
            InstallRunner.Request request;
            if (associate.isPresent()) {
                List<String> assoc = associate.get() ? manifest.integration().defaultAssociationExtensions() : List.of();
                request = InstallRunner.Request.of(manifest, platform, destination, selection.components(), inputs,
                        answers.sources(), assoc, cache);
            } else {
                request = InstallRunner.Request.of(manifest, platform, destination, selection.components(), inputs,
                        answers.sources(), cache);
            }
            // PATH entries: --no-path disables them, else the answer file's choice, else the manifest default (E13-S03).
            boolean addPath = !args.noPath()
                    && file.flatMap(AnswerFile::addPathEntries).orElse(!manifest.integration().pathEntries().isEmpty());
            request = request.withPathEntries(addPath);
            checkSpace(platform, manifest, destination, request);

            if (args.update()) {
                reporter.line("cli.updating", manifest.product().name(), selection.previousVersion(),
                        manifest.product().version(), destination);
            } else if (args.repair()) {
                reporter.line("cli.repairing", manifest.product().name(), manifest.product().version(), destination);
            } else {
                reporter.line("cli.installing", manifest.product().name(), manifest.product().version(), destination);
            }
            InstallRunner runner = new InstallRunner(resolver,
                    Optional.of(registries.apply(manifest.product().id())));
            if (args.dryRun()) {
                reporter.line("cli.dryRun");
                runner.dryRun(request).forEach(reporter::raw);
                return finish(0, messages.get(locale, "cli.dryRun"), manifest, destination, platform, realRun, reporter);
            }

            if (!args.waitPids().isEmpty()) {
                reporter.line("cli.waiting", args.waitPids());
                ProcessWaiter.awaitExit(args.waitPids(), ProcessWaiter.configuredTimeout());
            }

            Engine.Result result = runner.run(request, reporter, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
            if (result.succeeded()) {
                List<String> continued = new ArrayList<>();
                result.continuedFailures().forEach(r -> continued.add(r.step().id()));
                if (!continued.isEmpty()) {
                    reporter.line("progress.continued", continued.size(), String.join(", ", continued));
                }
                code = 0;
                message = messages.get(locale, "cli.done", manifest.product().version());
                reporter.line("cli.done", manifest.product().version());
            } else {
                InstallerException failure = result.failure().orElseGet(
                        () -> new InstallerException(ErrorCode.GENERAL, "installation failed without a cause", ""));
                report(failure, messages, locale);
                code = failure.exitCode();
                message = failure.getMessage();
                if (result.rollback().isPresent()) {
                    // The exit code stays the failure's; the message says whether the folder is as it was.
                    Rollback.Report rollback = result.rollback().get();
                    message += " (" + messages.get(locale, rollback.clean() ? "cli.rolledBack.short" : "cli.rollbackFailed.short") + ")";
                    if (!rollback.clean()) {
                        err.println(messages.get(locale, "error.rollback.hint", LogSetup.currentLogFile()));
                    }
                }
            }
            return finish(code, message, manifest, destination, platform, realRun, reporter);
        } catch (RuntimeException e) {
            InstallerException failure = InstallerException.wrap(e);
            report(failure, messages, locale);
            return finish(failure.exitCode(), failure.getMessage(), manifest, destination, platform, realRun, null);
        } finally {
            // A torrent download leaves bt running; the process must not hang on its threads (E08-S02).
            TorrentRuntime.shutdownShared();
        }
    }

    // --- uninstall (E14-S03, E15-S04) ----------------------------------------------

    /**
     * {@code --uninstall}: the record decides what goes; the safe default keeps
     * what the user changed or added, {@code --purge} takes everything. Exit
     * 0 when everything set out to be removed is gone (kept files aside),
     * 1 when items failed, 7 when interrupted, 11 without a usable record.
     */
    private int uninstall(InstallManifest manifest, Platform platform, Path destination, Messages messages,
                          Locale locale, ConsoleReporter reporter, boolean realRun) {
        InstallationRecord record = readRecord(manifest, destination);
        // Elevate when the destination needs rights (E14-S03).
        Uninstaller uninstaller = new Uninstaller(manifest, platform, record,
                Optional.of(registries.apply(manifest.product().id())),
                Optional.of(de.yawi.installer.core.elevation.Elevation.forRuntime()));
        Uninstaller.Options options = args.purge() ? Uninstaller.Options.PURGE : Uninstaller.Options.SAFE;
        reporter.line("cli.uninstalling", manifest.product().name(), record.productVersion(), destination);
        if (args.dryRun()) {
            reporter.line("cli.dryRun");
            uninstaller.dryRun(options).forEach(reporter::raw);
            return finish(0, messages.get(locale, "cli.dryRun"), manifest, destination, platform, realRun, reporter);
        }
        if (!args.waitPids().isEmpty()) {
            reporter.line("cli.waiting", args.waitPids());
            ProcessWaiter.awaitExit(args.waitPids(), ProcessWaiter.configuredTimeout());
        }
        Uninstaller.Report report = uninstaller.run(options, reporter, new CancellationToken());
        int code;
        String message;
        if (report.cancelled()) {
            InstallerException failure = new CancelledException();
            report(failure, messages, locale);
            code = failure.exitCode();
            message = failure.getMessage();
        } else if (report.clean()) {
            code = 0;
            String key = report.keptAnything() ? "cli.uninstall.done.kept" : "cli.uninstall.done";
            int kept = report.keptModified().size() + report.keptUnrecorded().size();
            message = messages.get(locale, key, kept);
            reporter.line(key, kept);
        } else {
            InstallerException failure = new UninstallFailedException(report.failures());
            report(failure, messages, locale);
            code = failure.exitCode();
            message = failure.getMessage();
        }
        return finish(code, message, manifest, destination, platform, realRun, reporter);
    }

    /** The record of the installation under {@code destination}, or exit 11 with the reason in the log. */
    private InstallationRecord readRecord(InstallManifest manifest, Path destination) {
        Path file = RecordWriter.defaultFile(destination);
        if (!Files.isRegularFile(file)) {
            throw new NoInstallationException(destination, "no record at " + file, null);
        }
        InstallationRecord previous;
        try {
            previous = RecordReader.read(file);
        } catch (IOException | RuntimeException e) {
            throw new NoInstallationException(destination, "record " + file + " unusable: " + e.getMessage(), e);
        }
        if (!sameDirectory(previous.destination(), destination)) {
            throw new NoInstallationException(destination, "record belongs to " + previous.destination(), null);
        }
        if (!previous.productId().equals(manifest.product().id())) {
            throw new NoInstallationException(destination, "record is for product '" + previous.productId()
                    + "', this installer is for '" + manifest.product().id() + "'", null);
        }
        return previous;
    }

    /** The end of a real run: result file first, then the relaunch, both best effort. */
    private int finish(int code, String message, InstallManifest manifest, Path destination, Platform platform,
                       boolean realRun, ConsoleReporter reporter) {
        if (!realRun) {
            return code;
        }
        if (args.result() != null) {
            ResultFile.write(Path.of(args.result()), code, manifest == null ? "" : manifest.product().version(),
                    Instant.now(), message);
        }
        if (args.relaunch()) {
            relaunch(manifest, destination, platform, reporter);
        }
        LOG.info("Silent run ended with exit code {}: {}", code, message);
        return code;
    }

    /** {@code --relaunch}: the first shortcut's target - the same thing the finish page launches (E13). */
    private void relaunch(InstallManifest manifest, Path destination, Platform platform, ConsoleReporter reporter) {
        if (manifest == null || destination == null || platform == null) {
            LOG.warn("--relaunch: nothing known to launch (run ended before the destination was decided)");
            return;
        }
        Optional<Path> launch = LaunchTarget.of(manifest, platform, destination);
        if (launch.isEmpty()) {
            LOG.warn("--relaunch: the manifest declares no (usable) shortcut, nothing to launch");
            if (reporter != null) {
                reporter.line("cli.relaunch.none");
            }
            return;
        }
        Path executable = launch.get();
        try {
            if (reporter != null) {
                reporter.line("cli.relaunch", executable);
            }
            AppLauncher.launchDetached(platform, executable, destination);
        } catch (IOException | RuntimeException e) {
            LOG.warn("--relaunch: {} could not be started: {}", executable, e.toString());
        }
    }

    private ConsoleReporter.Mode mode() {
        if (args.quiet()) {
            return ConsoleReporter.Mode.QUIET;
        }
        return args.json() ? ConsoleReporter.Mode.JSON : ConsoleReporter.Mode.TEXT;
    }

    /** The user's text and hint on stderr, the technical side in the log. */
    private void report(InstallerException failure, Messages messages, Locale locale) {
        if (failure.code() == ErrorCode.CANCELLED) {
            // Not a defect (a declined rights prompt, E12-S02-T06): no hint, no log pointer.
            LOG.info("Silent run cancelled: {}", failure.getMessage());
            err.println(failure.userMessage(messages, locale));
            return;
        }
        if (failure.code() == ErrorCode.GENERAL) {
            LOG.error("Silent run failed [{}]", failure.code(), failure);
        } else {
            LOG.error("Silent run failed [{}]: {}", failure.code(), failure.getMessage());
        }
        err.println(failure.userMessage(messages, locale));
        err.println(failure.hint(messages, locale));
        failure.detail().ifPresent(err::println);
        err.println(messages.get(locale, "error.dialog.logHint", LogSetup.currentLogFile()));
    }

    // --- destination -----------------------------------------------------------

    private Path destination(InstallManifest manifest, Platform platform, Answers answers, Messages messages,
                             Locale locale) {
        String text = answers.dest().orElse(null);
        if (text == null) {
            var config = manifest.destination();
            String manifestDefault = (answers.allUsers() ? config.systemWideFor(platform.os())
                    : config.defaultFor(platform.os())).orElse(null);
            text = platform.installDir(manifestDefault, manifest.product().id(), answers.allUsers()).toString();
        }
        DestinationValidator.Check check = new DestinationValidator(platform).check(text, 0, 0);
        for (DestinationValidator.Problem problem : check.problems()) {
            if (!problem.isError()) {
                LOG.info("Destination {}: {}", text, messages.get(Locale.ENGLISH, problem.key(), problem.args()));
                continue;
            }
            throw destinationProblem(problem, check, text, messages, locale);
        }
        // A destination that needs administrator rights is fine: the runner obtains them (E12).
        return check.path();
    }

    private static InstallerException destinationProblem(DestinationValidator.Problem problem,
                                                         DestinationValidator.Check check, String text,
                                                         Messages messages, Locale locale) {
        return switch (problem.key()) {
            case DestinationValidator.NOT_WRITABLE -> new PermissionDeniedException(check.path(), null);
            case DestinationValidator.NOT_ENOUGH_SPACE -> new InsufficientSpaceException(check.path(),
                    (Long) problem.args()[0], (Long) problem.args()[1]);
            default -> new InvalidArgumentsException("--dest=" + text,
                    messages.get(locale, problem.key(), problem.args()));
        };
    }

    /** The selection's own size against the free space, once the selection is known. */
    private static void checkSpace(Platform platform, InstallManifest manifest, Path destination,
                                   InstallRunner.Request request) {
        long required = new ComponentSelection(manifest, platform.os()).installBytes(request.selected());
        DestinationValidator.Check check = new DestinationValidator(platform).check(destination.toString(), required,
                manifest.destination().minFreeBytes());
        check.problems().stream()
                .filter(p -> p.isError() && p.key().equals(DestinationValidator.NOT_ENOUGH_SPACE))
                .findFirst()
                .ifPresent(p -> {
                    throw new InsufficientSpaceException(destination, (Long) p.args()[0], (Long) p.args()[1]);
                });
    }

    // --- selection and inputs ---------------------------------------------------

    /** @param previousVersion the installed version when updating, else null */
    record Selection(List<String> components, Map<String, String> values, String previousVersion) {
    }

    /** Arguments over answer file over the manifest's typical selection; file ids were checked by the reader. */
    private Selection fromAnswers(InstallManifest manifest, Platform platform, Answers answers) {
        ComponentSelection selection = new ComponentSelection(manifest, platform.os());
        List<String> ids;
        if (answers.components().isEmpty()) {
            ids = List.copyOf(selection.initialSelection());
        } else {
            for (String id : answers.components()) {
                if (manifest.component(id).isEmpty()) {
                    throw new InvalidArgumentsException("--components=" + id, "unknown component id");
                }
            }
            ids = answers.components();
        }
        return new Selection(ids, answers.inputs(), null);
    }

    /**
     * {@code --update}/{@code --repair}: the record decides what is installed;
     * the arguments may still override inputs. A repair is a re-install of the
     * same version, so the "not older" line is only worth a warning for an update.
     */
    private Selection fromRecord(InstallManifest manifest, Path destination, boolean repair) {
        InstallationRecord previous = readRecord(manifest, destination);
        RecordSelection recorded = RecordSelection.from(manifest, previous);
        for (String id : recorded.dropped()) {
            LOG.warn("Component '{}' from the record no longer exists in version {}; skipped", id,
                    manifest.product().version());
        }
        List<String> ids = recorded.components();
        Map<String, String> values = new LinkedHashMap<>(recorded.inputs());
        values.putAll(args.inputs());
        LOG.info("Updating {} {} -> {} in {}: components {}", previous.productId(), previous.productVersion(),
                manifest.product().version(), destination, ids);
        if (!repair && !Version.isNewer(manifest.product().version(), previous.productVersion())) {
            // Allowed (a re-install repairs), but worth a line: the caller normally only updates upwards.
            LOG.warn("Installed version {} is not older than this installer's {}", previous.productVersion(),
                    manifest.product().version());
        }
        return new Selection(ids, values, previous.productVersion());
    }

    private static boolean sameDirectory(Path a, Path b) {
        try {
            return a.toRealPath().equals(b.toRealPath());
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    /**
     * Every input of a selected component: given value or default, each
     * through the validator. A problem names where the value came from -
     * the argument, or the answer file's {@code <input>} (a missing
     * mandatory value with a file in play is the file's fault: it was meant
     * to be complete).
     */
    private Map<String, String> validatedInputs(InstallManifest manifest, Selection selection, Answers answers,
                                                Messages messages, Locale locale) {
        Set<String> selected = manifest.resolveSelection(selection.components());
        Map<String, String> values = new LinkedHashMap<>();
        for (Component c : manifest.components()) {
            if (!selected.contains(c.id())) {
                continue;
            }
            for (ComponentInput input : c.inputs()) {
                boolean given = selection.values().containsKey(input.id());
                String value = selection.values().getOrDefault(input.id(), InputValidator.defaultValue(input));
                InputValidator.validate(input, value).ifPresent(problem -> {
                    String text = messages.get(locale, problem.key(), problem.args());
                    if (answers.fromFile().isPresent() && !args.update()
                            && (!given || answers.inputFromFile(args, input.id()))) {
                        throw new AnswerFileException(Path.of(args.config()), given
                                ? "<input id=\"" + input.id() + "\" value=\"" + value + "\">: " + text
                                : "<input id=\"" + input.id() + "\"> is missing: " + text);
                    }
                    throw new InvalidArgumentsException("--input." + input.id() + "=" + value, text);
                });
                values.put(input.id(), value);
            }
        }
        for (String id : selection.values().keySet()) {
            if (!values.containsKey(id)) {
                LOG.warn("Input '{}' belongs to no selected component; ignored", id);
            }
        }
        return values;
    }

    private Optional<LocalCache> openCache() {
        if (args.cache() == null) {
            return Optional.empty();
        }
        try {
            LocalCache cache = LocalCache.open(Path.of(args.cache()));
            LOG.info("Cache {} with {} entries", cache.dir(), cache.size());
            return Optional.of(cache);
        } catch (IOException e) {
            throw new InvalidArgumentsException("--cache=" + args.cache(), e.getMessage());
        }
    }

    // --- help --------------------------------------------------------------------

    static final List<String> OPTIONS = List.of("silent", "update", "repair", "uninstall", "purge", "manifest", "config", "dest",
            "allUsers", "components", "input", "lang", "acceptLicense", "cache", "waitPid", "relaunch", "result", "log",
            "dryRun", "quiet", "output", "help", "version");

    private void printHelp(Messages messages, Locale locale) {
        out.println(messages.get(locale, "cli.help.usage"));
        out.println();
        out.println(messages.get(locale, "cli.help.intro"));
        out.println();
        for (String option : OPTIONS) {
            out.println("  " + messages.get(locale, "cli.help." + option));
        }
        out.println();
        out.println(messages.get(locale, "cli.help.exitCodes"));
        out.println("  0  " + messages.get(locale, "cli.help.exit.ok"));
        for (ErrorCode code : ErrorCode.values()) {
            out.println("  " + String.format("%-3d", code.exitCode()) + messages.get(locale, "cli.help.exit." + code.name()));
        }
    }
}
