package de.yawi.installer.ui;

import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The net under everything: logs an uncaught exception with
 * its stack trace and shows it through the error dialog - on the JavaFX
 * thread, one dialog at a time. While a dialog is open further failures are
 * only logged, so a storm of exceptions cannot bury the user in windows.
 * A {@code CancelledException} is never an error.
 */
public final class UncaughtHandler implements Thread.UncaughtExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(UncaughtHandler.class);

    private final Consumer<InstallerException> show;
    private final AtomicBoolean showing = new AtomicBoolean();

    /** @param show displays the failure; called on the JavaFX thread and expected to block while the dialog is open */
    public UncaughtHandler(Consumer<InstallerException> show) {
        this.show = show;
    }

    /** Installs this handler for the current (JavaFX) thread and as the default for all others. */
    public void install() {
        Thread.currentThread().setUncaughtExceptionHandler(this);
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        InstallerException failure = InstallerException.wrap(throwable);
        if (failure.code() == ErrorCode.CANCELLED) {
            LOG.info("Cancelled on thread {}", thread.getName());
            return;
        }
        LOG.error("Uncaught exception on thread {} [{}]", thread.getName(), failure.code(), throwable);
        if (!showing.compareAndSet(false, true)) {
            LOG.warn("Error dialog already open; not showing another one");
            return;
        }
        Runnable display = () -> {
            try {
                show.accept(failure);
            } catch (RuntimeException e) {
                LOG.error("Error dialog itself failed", e);
            } finally {
                showing.set(false);
            }
        };
        if (javafx.application.Platform.isFxApplicationThread()) {
            display.run();
        } else {
            try {
                javafx.application.Platform.runLater(display);
            } catch (IllegalStateException toolkitDown) {
                showing.set(false);
                LOG.warn("No JavaFX toolkit to show the error", toolkitDown);
            }
        }
    }

    /** True while a dialog is on screen; for tests. */
    public boolean isShowing() {
        return showing.get();
    }
}
