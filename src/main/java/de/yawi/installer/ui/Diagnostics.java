package de.yawi.installer.ui;

import de.yawi.installer.core.error.DiagnosticReport;
import de.yawi.installer.core.engine.Uninstaller;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.RecordWriter;
import javafx.application.HostServices;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * "Open log" and "Save report" as the finish page and the error dialog need
 * them: builds the {@link DiagnosticReport.Context} from the
 * model, asks where to save, opens the log in the system viewer.
 */
public final class Diagnostics {

    private static final Logger LOG = LoggerFactory.getLogger(Diagnostics.class);

    private Diagnostics() {
    }

    /** The report for the wizard's current state; {@code failure} overrides the model's install result. */
    public static String report(InstallerModel model, Optional<InstallerException> failure) {
        InstallManifest manifest = model.getManifest();
        Set<String> selected = manifest.resolveSelection(model.getSelectedComponents());
        Map<String, String> inputs = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Component c : manifest.components()) {
            c.inputs().forEach(i -> {
                labels.put(i.id(), i.label() == null ? "" : i.label());
                if (selected.contains(c.id()) && model.getInputs().containsKey(i.id())) {
                    inputs.put(i.id(), model.getInputs().get(i.id()));
                }
            });
        }
        Path destination = model.getEffectiveDestination();
        Optional<InstallerException> cause = failure.isPresent() ? failure
                : model.getInstallResult().flatMap(r -> r.failure());
        String report = DiagnosticReport.build(new DiagnosticReport.Context(
                Optional.of(manifest), model.getPlatform(), Optional.of(destination), selected,
                InstallationRecord.withoutSecrets(inputs, labels),
                Optional.of(RecordWriter.defaultFile(destination)), cause, LogSetup.currentLogFile()));
        return model.getUninstallReport().map(u -> report + "\n" + uninstallSection(u)).orElse(report);
    }

    /** What the uninstall did, appended to the report: counts, what stayed, what failed. */
    static String uninstallSection(Uninstaller.Report report) {
        StringBuilder sb = new StringBuilder("== Uninstall ==\n");
        sb.append("deleted: ").append(report.deleted()).append(", steps reversed: ").append(report.stepsReversed())
                .append(", failures: ").append(report.failures().size())
                .append(report.cancelled() ? ", cancelled" : "")
                .append(report.destinationRemoved() ? ", destination removed" : "").append('\n');
        report.keptModified().forEach(p -> sb.append("kept (changed since the installation): ").append(p).append('\n'));
        report.keptUnrecorded().forEach(p -> sb.append("kept (not installed by the installer): ").append(p).append('\n'));
        report.stepsWithoutReverse().forEach(s -> sb.append("no reverse operation: ").append(s).append('\n'));
        report.failures().forEach(f -> sb.append("failed: ").append(f).append('\n'));
        return sb.toString();
    }

    /** A report before the model exists (startup failure): platform, log and whatever manifest there is. */
    public static String startupReport(Platform platform, Optional<InstallManifest> manifest,
                                       InstallerException failure) {
        return DiagnosticReport.build(new DiagnosticReport.Context(manifest, platform, Optional.empty(),
                Set.of(), Map.of(), Optional.empty(), Optional.of(failure), LogSetup.currentLogFile()));
    }

    /**
     * Asks for a file and writes the report. Returns the file, or empty if the
     * user cancelled.
     *
     * @throws IOException if writing fails
     */
    public static Optional<Path> saveReport(Window owner, Platform platform, String title, String report)
            throws IOException {
        Optional<Path> chosen = chooseSaveFile(owner, platform, title, DiagnosticReport.defaultFileName(), "Text", "*.txt");
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(DiagnosticReport.save(report, chosen.get()));
    }

    /**
     * The save dialog the finish page uses for its files: starts on the
     * desktop (else home), proposes a name and one extension filter.
     *
     * @return the chosen file, or empty if the user cancelled
     */
    public static Optional<Path> chooseSaveFile(Window owner, Platform platform, String title, String initialName,
                                                String filterName, String filterPattern) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialFileName(initialName);
        Path start = platform.desktopDir();
        if (!Files.isDirectory(start)) {
            start = platform.homeDir();
        }
        if (Files.isDirectory(start)) {
            chooser.setInitialDirectory(start.toFile());
        }
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(filterName, filterPattern));
        File chosen = chooser.showSaveDialog(owner);
        return chosen == null ? Optional.empty() : Optional.of(chosen.toPath());
    }

    /** Opens the current log in the system's viewer; logs instead if that is impossible. */
    public static void openLog(Optional<HostServices> host) {
        Path log = LogSetup.currentLogFile();
        if (!Files.exists(log)) {
            LOG.warn("Log file {} does not exist", log);
            return;
        }
        host.ifPresentOrElse(h -> h.showDocument(log.toUri().toString()),
                () -> LOG.warn("No host services, cannot open {}", log));
    }
}
