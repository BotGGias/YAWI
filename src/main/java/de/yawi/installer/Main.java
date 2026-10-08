package de.yawi.installer;

import de.yawi.installer.cli.Arguments;
import de.yawi.installer.cli.SilentRun;
import de.yawi.installer.core.elevation.ElevatedWorker;
import de.yawi.installer.core.elevation.HandoverDir;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.InvalidArgumentsException;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.i18n.Messages;
import de.yawi.installer.ui.WizardApp;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The one entry point. Decides from the arguments alone
 * whether to open the wizard or to run without a window; JavaFX is only
 * touched on the wizard path, so {@code --silent}, {@code --help} and
 * {@code --version} work on a machine without a display.
 *
 * <p>Deliberately not a JavaFX {@code Application}: the class with
 * {@code main} must not extend it, or the toolkit initialises before the
 * decision is made.
 */
public final class Main {

    /** The elevated child's option, internal: {@code --elevated-run=<hand-over directory>}. */
    public static final String ELEVATED_RUN = "--elevated-run=";
    /** The elevated uninstall child's option, internal: {@code --elevated-uninstall=<hand-over directory>}. */
    public static final String ELEVATED_UNINSTALL = "--elevated-uninstall=";

    private Main() {
    }

    public static void main(String[] args) {
        // Before the first network access: HttpClient then honours the system proxy (E07-S02).
        System.setProperty("java.net.useSystemProxies", "true");

        // The elevated process: decided before anything else runs, so the log lands in the hand-over
        // directory from the first line on and never as root in the temp directory.
        for (String arg : args) {
            if (arg.startsWith(ELEVATED_RUN)) {
                Path workdir = Path.of(arg.substring(ELEVATED_RUN.length()));
                System.setProperty(LogSetup.PROPERTY, workdir.toAbsolutePath().toString());
                System.setProperty(LogSetup.FILE_PROPERTY, HandoverDir.LOG);
                System.exit(ElevatedWorker.run(workdir));
                return;
            }
            if (arg.startsWith(ELEVATED_UNINSTALL)) {
                Path workdir = Path.of(arg.substring(ELEVATED_UNINSTALL.length()));
                System.setProperty(LogSetup.PROPERTY, workdir.toAbsolutePath().toString());
                System.setProperty(LogSetup.FILE_PROPERTY, HandoverDir.LOG);
                System.exit(ElevatedWorker.runUninstall(workdir));
                return;
            }
        }

        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (InvalidArgumentsException e) {
            Messages messages = Messages.load();
            System.err.println(e.userMessage(messages, Locale.getDefault()));
            System.err.println(e.hint(messages, Locale.getDefault()));
            System.exit(e.exitCode());
            return;
        }
        if (arguments.headless()) {
            System.exit(new SilentRun(arguments, System.out, System.err).run());
            return;
        }
        int code = startWizard(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    /** The wizard path: everything that references JavaFX lives behind this call. */
    private static int startWizard(String[] args) {
        try {
            return WizardApp.run(args);
        } catch (RuntimeException | Error e) {
            // The toolkit could not start or start() rethrew: no window to show it in.
            InstallerException failure = InstallerException.wrap(e);
            Messages messages = Messages.load();
            org.slf4j.LoggerFactory.getLogger(Main.class).error("Installer ended with [{}]", failure.code(), e);
            System.err.println(failure.userMessage(messages, Locale.getDefault()));
            System.err.println(failure.hint(messages, Locale.getDefault()));
            System.err.println("Log: " + LogSetup.currentLogFile());
            return failure.exitCode();
        }
    }
}
