package de.yawi.installer.ui;

import de.yawi.installer.cli.Arguments;
import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.i18n.Messages;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestLocator;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.state.InstallationRegistry;
import javafx.application.Application;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * The JavaFX side of the installer: the wizard window. Started only by
 * {@link de.yawi.installer.Main} when the arguments ask for no other mode
 * ; never has a {@code main} of its own.
 */
public class WizardApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(WizardApp.class);

    private InstallerModel model;
    private de.yawi.installer.core.platform.Platform platform;
    private InstallManifest manifest;
    /** Set by a startup failure; {@link #main} turns it into the process exit status. */
    private static volatile int exitCode;

    @Override
    public void start(Stage stage) {
        // Loaded before anything can fail, so even the startup error dialog
        // speaks the system language. A missing base bundle is a build defect.
        Messages messages = Messages.load();
        try {
            // Refuses to run on an unknown OS or architecture.
            platform = PlatformFactory.current();
            // Whatever slips through from here on ends in the error dialog, not on stderr.
            new UncaughtHandler(failure -> ErrorDialog.show(stage.isShowing() ? stage : null,
                    environment(messages), failure)).install();
            LOG.info("Starting installer on {} {} ({}) - detected {}",
                    System.getProperty("os.name"),
                    System.getProperty("os.version"),
                    System.getProperty("os.arch"),
                    platform);

            // The same parser as the entry point; Main already rejected anything invalid.
            String explicitManifest = Arguments.parse(getParameters().getRaw().toArray(new String[0])).manifest();
            manifest = ManifestLocator.forRuntime().locate(explicitManifest);
            // From here on the log lives where the platform keeps logs
            // the lines above stay in the temp file.
            LogSetup.redirect(platform.logDir(manifest.product().id()));

            model = new InstallerModel(manifest, platform);
            model.setHostServices(getHostServices());
            // The register tells whether this product is already installed;
            // a few small files, read here so the maintenance page can decide to show itself.
            InstallationRegistry registry = InstallationRegistry.forPlatform(platform, manifest.product().id());
            model.setRegistry(registry);
            model.getExistingInstallations().setAll(registry.installations());

            // Pages in manifest order, all loaded up front so going back
            // shows them as the user left them; the frame owns the buttons.
            List<WizardPage> pages = PageRegistry.build(model);
            WizardFlow flow = new WizardFlow(pages);
            WizardFrameController.show(stage, model, flow);
            stage.show();
        } catch (RuntimeException e) {
            InstallerException failure = InstallerException.wrap(e);
            // A known cause is fully described by its message; only the
            // unexpected needs the stack trace in the log.
            if (failure.code() == ErrorCode.GENERAL) {
                LOG.error("Installer failed to start [{}]", failure.code(), failure);
            } else {
                LOG.error("Installer failed to start [{}]: {}", failure.code(), failure.getMessage());
            }
            if (platform == null) {
                // No platform: the dialog cannot even build a report; a plain message must do.
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                        failure.userMessage(messages, Locale.getDefault())).showAndWait();
            } else {
                ErrorDialog.show(null, environment(messages), failure);
            }
            // Not rethrown: JavaFX would wrap it and lose the exit code.
            exitCode = failure.exitCode();
            javafx.application.Platform.exit();
        }
    }

    /**
     * What the error dialog needs: texts in the wizard's language once there
     * is a model, in the system's before; the report from the model if it
     * exists, else from platform, log and manifest alone.
     */
    private ErrorDialog.Environment environment(Messages messages) {
        Locale locale = model != null ? model.getLocale() : Locale.getDefault();
        return new ErrorDialog.Environment(messages, locale, platform,
                java.util.Optional.ofNullable(getHostServices()),
                failure -> model != null ? Diagnostics.report(model, java.util.Optional.of(failure))
                        : Diagnostics.startupReport(platform, java.util.Optional.ofNullable(manifest), failure));
    }

    /** Runs after the last window closed, whichever way it closed. */
    @Override
    public void stop() {
        if (model != null) {
            model.shutdown();
        }
    }

    /**
     * Runs the wizard until its last window closed and returns the process
     * exit status: 0, or the code of a startup failure. Throws if the toolkit
     * itself could not start.
     */
    public static int run(String[] args) {
        exitCode = 0;
        launch(WizardApp.class, args);
        return exitCode;
    }
}
