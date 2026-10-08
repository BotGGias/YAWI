package de.yawi.installer.ui;

import de.yawi.installer.core.download.SourceResolver;
import de.yawi.installer.core.elevation.Elevation;
import de.yawi.installer.core.engine.Uninstaller;
import de.yawi.installer.core.i18n.ManifestMessages;
import de.yawi.installer.core.i18n.Messages;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.state.ExistingInstallation;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRegistry;
import de.yawi.installer.core.state.RecordSelection;
import de.yawi.installer.ui.i18n.LocaleManager;
import javafx.application.HostServices;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.collections.ObservableSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.Optional;

/**
 * Holds everything the user chooses while walking through the wizard, plus
 * the manifest that drives it.
 *
 * <p>One instance is created at startup and passed to every page controller, so
 * the pages share state without reaching for a static field.
 */
public class InstallerModel {

    private static final Logger LOG = LoggerFactory.getLogger(InstallerModel.class);

    /**
     * What the run at the end of the wizard is. {@code FRESH} is a
     * new installation; the maintenance page sets the others for a
     * registered one. {@code MODIFY} and {@code UPDATE} run as a full pass
     * over the existing installation with the previous answers prefilled;
     * {@code UNINSTALL} skips the manifest pages and runs the
     * {@code Uninstaller} from the progress page; {@code REPAIR} waits for
     * .
     */
    public enum InstallMode { FRESH, MODIFY, REPAIR, UNINSTALL, UPDATE }

    private final InstallManifest manifest;
    private final Platform platform;

    /** Owns the locale property; the accessors below delegate to it. */
    private final LocaleManager i18n;
    private final ObjectProperty<Path> destination = new SimpleObjectProperty<>();
    /** "For all users" on the destination page (E06-S03); E13 places menu entries accordingly. */
    private final BooleanProperty systemWide = new SimpleBooleanProperty(this, "systemWide", false);
    /**
     * Whether the chosen destination is only writable with elevated rights,
     * as found by the destination check; asks for them when
     * the installation starts.
     */
    private final BooleanProperty elevationRequired = new SimpleBooleanProperty(this, "elevationRequired", false);
    private final ObservableSet<String> selectedComponents = FXCollections.observableSet();

    /**
     * Values of component specific inputs, keyed by input id.
     *
     * <p>This is the shape needs for manifest driven inputs and resolves
     * as {@code ${input.<id>}}, which is why the server port lives here instead
     * of in a dedicated property.
     */
    private final ObservableMap<String, String> inputs = FXCollections.observableHashMap();

    /**
     * How each source should be obtained, keyed by source id. Only
     * sources the user (or the version check) decided about are in here;
     * {@link #sourceChoice} supplies the default for the rest.
     */
    private final ObservableMap<String, de.yawi.installer.core.download.ProviderKind> sourceChoices =
            FXCollections.observableHashMap();

    /** Outcome shown on the finish page; the progress page sets it when the engine ends. */
    private final BooleanProperty installSucceeded = new SimpleBooleanProperty(this, "installSucceeded", true);
    /** The engine's full result once the installation ran; the diagnostic report reads the failure from it. */
    private final ObjectProperty<de.yawi.installer.core.engine.Engine.Result> installResult =
            new SimpleObjectProperty<>(this, "installResult");
    /** The finish page's "launch application" choice; acts on it. */
    private final BooleanProperty launchAfterFinish = new SimpleBooleanProperty(this, "launchAfterFinish", false);
    /** Whether the manifest's file associations are created; the default comes from the manifest. */
    private final BooleanProperty associateFileTypes = new SimpleBooleanProperty(this, "associateFileTypes", false);
    /** Whether the manifest's PATH entries are added; the default comes from the manifest. */
    private final BooleanProperty addPathEntries = new SimpleBooleanProperty(this, "addPathEntries", false);
    private HostServices hostServices;

    /**
     * What has to be stopped when the wizard ends, in registration order.
     * Downloads and the engine task register here so that a
     * cancel leaves no thread behind.
     */
    private final Deque<Runnable> shutdownHooks = new ArrayDeque<>();
    private volatile BooleanSupplier cancelHandler;
    private volatile java.util.function.Predicate<String> pageHandler;
    private Elevation elevation;
    private final BooleanProperty cancelLocked = new SimpleBooleanProperty(this, "cancelLocked", false);
    private SourceResolver sourceResolver;
    private InstallationRegistry registry;
    /** The product's registered installations, found at startup; the maintenance page removes orphans. */
    private final ObservableList<ExistingInstallation> existingInstallations = FXCollections.observableArrayList();
    /** The installation the maintenance page works on, if any. */
    private final ObjectProperty<ExistingInstallation> existingInstallation = new SimpleObjectProperty<>();
    private final ObjectProperty<InstallMode> installMode = new SimpleObjectProperty<>(this, "installMode", InstallMode.FRESH);
    /** The uninstall page's two questions; both off = the safe default. */
    private final BooleanProperty deleteModified = new SimpleBooleanProperty(this, "deleteModified", false);
    private final BooleanProperty deleteUnrecorded = new SimpleBooleanProperty(this, "deleteUnrecorded", false);
    /** What the uninstall did, set by the progress page; the finish page reads it. */
    private final ObjectProperty<Uninstaller.Report> uninstallReport = new SimpleObjectProperty<>(this, "uninstallReport");

    public InstallerModel(InstallManifest manifest, Platform platform) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.platform = Objects.requireNonNull(platform, "platform");
        // System language when the manifest offers it, its default otherwise.
        Locale initial = manifest.languages().resolve(Locale.getDefault(Locale.Category.DISPLAY));
        this.i18n = new LocaleManager(Messages.load(), new ManifestMessages(manifest), initial);
        // Pre-tick file associations when the manifest asks for them by default.
        this.associateFileTypes.set(!manifest.integration().defaultAssociationExtensions().isEmpty());
        // Pre-tick PATH entries when the manifest declares any.
        this.addPathEntries.set(!manifest.integration().pathEntries().isEmpty());
    }

    public InstallManifest getManifest() {
        return manifest;
    }

    /** The platform this installer runs on; pages use it for paths and checks. */
    public Platform getPlatform() {
        return platform;
    }

    /**
     * Languages offered on the welcome page, from the manifest's
     * {@code <languages>} block. Display names are derived from the locale
     * itself, so each language can be shown in its own language.
     */
    public List<Locale> getAvailableLanguages() {
        return manifest.languages().languages();
    }

    /** Texts and the current language; pages bind their labels through it. */
    public LocaleManager i18n() {
        return i18n;
    }

    public ObjectProperty<Locale> localeProperty() {
        return i18n.localeProperty();
    }

    public Locale getLocale() {
        return i18n.getLocale();
    }

    public void setLocale(Locale value) {
        i18n.setLocale(value);
    }

    public ObjectProperty<Path> destinationProperty() {
        return destination;
    }

    public Path getDestination() {
        return destination.get();
    }

    public void setDestination(Path value) {
        destination.set(value);
    }

    /**
     * The folder the installation would go to right now: the user's choice
     * or, until there is one, {@link #defaultDestination} for the
     * current scope.
     */
    public Path getEffectiveDestination() {
        Path chosen = destination.get();
        return chosen != null ? chosen : defaultDestination(isSystemWide());
    }

    /**
     * The proposed folder for a scope: the manifest's {@code <default os>} /
     * {@code <systemWide os>} for this OS, else the platform's built-in one.
     */
    public Path defaultDestination(boolean systemWide) {
        var config = manifest.destination();
        String manifestDefault = (systemWide ? config.systemWideFor(platform.os()) : config.defaultFor(platform.os()))
                .orElse(null);
        return platform.installDir(manifestDefault, manifest.product().id(), systemWide);
    }

    public BooleanProperty systemWideProperty() {
        return systemWide;
    }

    public boolean isSystemWide() {
        return systemWide.get();
    }

    public void setSystemWide(boolean value) {
        systemWide.set(value);
    }

    public BooleanProperty elevationRequiredProperty() {
        return elevationRequired;
    }

    public boolean isElevationRequired() {
        return elevationRequired.get();
    }

    public void setElevationRequired(boolean value) {
        elevationRequired.set(value);
    }

    public ObservableSet<String> getSelectedComponents() {
        return selectedComponents;
    }

    public ObservableMap<String, String> getInputs() {
        return inputs;
    }

    public ObservableMap<String, de.yawi.installer.core.download.ProviderKind> getSourceChoices() {
        return sourceChoices;
    }

    /** The chosen kind for a source, or the default: bundled if shipped, else download, else torrent. */
    public de.yawi.installer.core.download.ProviderKind sourceChoice(de.yawi.installer.core.manifest.Source source) {
        de.yawi.installer.core.download.ProviderKind chosen = sourceChoices.get(source.id());
        return chosen != null ? chosen : defaultSourceChoice(source);
    }

    public static de.yawi.installer.core.download.ProviderKind defaultSourceChoice(de.yawi.installer.core.manifest.Source source) {
        return de.yawi.installer.core.download.SourceResolver.defaultKind(source);
    }

    public BooleanProperty installSucceededProperty() {
        return installSucceeded;
    }

    public boolean isInstallSucceeded() {
        return installSucceeded.get();
    }

    public void setInstallSucceeded(boolean value) {
        installSucceeded.set(value);
    }

    public ObjectProperty<de.yawi.installer.core.engine.Engine.Result> installResultProperty() {
        return installResult;
    }

    public Optional<de.yawi.installer.core.engine.Engine.Result> getInstallResult() {
        return Optional.ofNullable(installResult.get());
    }

    public void setInstallResult(de.yawi.installer.core.engine.Engine.Result value) {
        installResult.set(value);
        installSucceeded.set(value != null && value.succeeded());
    }

    public BooleanProperty launchAfterFinishProperty() {
        return launchAfterFinish;
    }

    public boolean isLaunchAfterFinish() {
        return launchAfterFinish.get();
    }

    public BooleanProperty associateFileTypesProperty() {
        return associateFileTypes;
    }

    public boolean isAssociateFileTypes() {
        return associateFileTypes.get();
    }

    public void setAssociateFileTypes(boolean value) {
        associateFileTypes.set(value);
    }

    /**
     * The extensions of the file associations to create: the manifest's
     * defaults when the box is ticked, none otherwise.
     */
    public Set<String> enabledAssociations() {
        return associateFileTypes.get() ? Set.copyOf(manifest.integration().defaultAssociationExtensions()) : Set.of();
    }

    public BooleanProperty addPathEntriesProperty() {
        return addPathEntries;
    }

    public boolean isAddPathEntries() {
        return addPathEntries.get();
    }

    public void setAddPathEntries(boolean value) {
        addPathEntries.set(value);
    }

    /** Set by {@code Main}; pages use it to open the log in the system viewer. May be null in tests. */
    public void setHostServices(HostServices hostServices) {
        this.hostServices = hostServices;
    }

    public Optional<HostServices> getHostServices() {
        return Optional.ofNullable(hostServices);
    }

    /** Tests inject a resolver with tame downloaders; the wizard otherwise uses {@link SourceResolver#forRuntime()}. */
    public void setSourceResolver(SourceResolver resolver) {
        this.sourceResolver = resolver;
    }

    public Optional<SourceResolver> getSourceResolver() {
        return Optional.ofNullable(sourceResolver);
    }

    /** How administrator rights are obtained; tests inject {@link Elevation#direct()}, the wizard uses the runtime's. */
    public void setElevation(Elevation elevation) {
        this.elevation = elevation;
    }

    public Elevation getElevation() {
        return elevation == null ? Elevation.forRuntime() : elevation;
    }

    /** The register a successful installation is written to; absent in tests, which leave no trace. */
    public void setRegistry(InstallationRegistry registry) {
        this.registry = registry;
    }

    public Optional<InstallationRegistry> getRegistry() {
        return Optional.ofNullable(registry);
    }

    public ObservableList<ExistingInstallation> getExistingInstallations() {
        return existingInstallations;
    }

    public ObjectProperty<ExistingInstallation> existingInstallationProperty() {
        return existingInstallation;
    }

    public Optional<ExistingInstallation> getExistingInstallation() {
        return Optional.ofNullable(existingInstallation.get());
    }

    public void setExistingInstallation(ExistingInstallation value) {
        existingInstallation.set(value);
    }

    public ObjectProperty<InstallMode> installModeProperty() {
        return installMode;
    }

    public InstallMode getInstallMode() {
        return installMode.get();
    }

    public void setInstallMode(InstallMode value) {
        installMode.set(Objects.requireNonNull(value, "installMode"));
    }

    public boolean isUninstalling() {
        return installMode.get() == InstallMode.UNINSTALL;
    }

    public BooleanProperty deleteModifiedProperty() {
        return deleteModified;
    }

    public BooleanProperty deleteUnrecordedProperty() {
        return deleteUnrecorded;
    }

    /** The uninstall page's answers as the uninstaller wants them. */
    public Uninstaller.Options uninstallOptions() {
        return new Uninstaller.Options(deleteModified.get(), deleteUnrecorded.get());
    }

    public ObjectProperty<Uninstaller.Report> uninstallReportProperty() {
        return uninstallReport;
    }

    public Optional<Uninstaller.Report> getUninstallReport() {
        return Optional.ofNullable(uninstallReport.get());
    }

    /** Also sets {@link #installSucceededProperty()}: the finish page's success/failure switch is shared. */
    public void setUninstallReport(Uninstaller.Report value) {
        uninstallReport.set(value);
        installSucceeded.set(value != null && value.clean());
    }

    /**
     * Takes destination, scope, components and inputs from a previous
     * installation's record. Inputs go first so the input
     * form, rebuilt when the component set changes, picks them up; the
     * component ids are resolved here because the components page only
     * resolves a preselection on its first visit.
     */
    public void prefillFrom(InstallationRecord record) {
        RecordSelection recorded = RecordSelection.from(manifest, record);
        for (String id : recorded.dropped()) {
            LOG.warn("Component '{}' from the record no longer exists in version {}; skipped", id,
                    manifest.product().version());
        }
        inputs.putAll(recorded.inputs());
        Set<String> resolved = manifest.resolveSelection(recorded.components());
        selectedComponents.retainAll(resolved);
        selectedComponents.addAll(resolved);
        setSystemWide(platform.isSystemPath(record.destination()));
        setDestination(record.destination());
        LOG.info("Prefilled from the installation under {}: components {}, {} input(s)", record.destination(),
                resolved, recorded.inputs().size());
    }

    /**
     * Registers who handles a Cancel while the installation runs:
     * the progress page, which stops the task and lets the rollback finish
     * in the open window. Cleared by passing null.
     */
    public void onCancelRequest(BooleanSupplier handler) {
        this.cancelHandler = handler;
    }

    /**
     * Registers who handles a page's wish to jump back to another page
     * (the summary's "change destination"); the frame, which owns
     * the flow. Cleared by passing null.
     */
    public void onPageRequest(java.util.function.Predicate<String> handler) {
        this.pageHandler = handler;
    }

    /** True if the page was entered. */
    public boolean requestPage(String pageName) {
        java.util.function.Predicate<String> handler = pageHandler;
        return handler != null && handler.test(pageName);
    }

    /** True if a running installation took the cancel; the window must stay open until it is done. */
    public boolean requestCancel() {
        BooleanSupplier handler = cancelHandler;
        return handler != null && handler.getAsBoolean();
    }

    /** True while the rollback runs: nothing to cancel any more, the button is disabled. */
    public BooleanProperty cancelLockedProperty() {
        return cancelLocked;
    }

    /** Registers work to stop on {@link #shutdown()}; hooks run in reverse order. */
    public void onShutdown(Runnable hook) {
        shutdownHooks.push(Objects.requireNonNull(hook, "hook"));
    }

    /**
     * Runs and clears every hook, latest first. A failing hook is logged and
     * does not stop the others, so shutdown always completes.
     */
    public void shutdown() {
        while (!shutdownHooks.isEmpty()) {
            Runnable hook = shutdownHooks.pop();
            try {
                hook.run();
            } catch (RuntimeException e) {
                LOG.warn("Shutdown hook failed", e);
            }
        }
    }
}
