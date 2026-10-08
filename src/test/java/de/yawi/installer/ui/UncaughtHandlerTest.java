package de.yawi.installer.ui;

import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E17-S02-T02: everything uncaught reaches the dialog once; cancels never do. */
class UncaughtHandlerTest {

    @BeforeAll
    static void startToolkit() {
        WizardNavigationTest.startToolkit();
    }

    @Test
    void showsOneDialogAtATimeAndWrapsTheThrowable() throws Exception {
        List<InstallerException> shown = new ArrayList<>();
        CountDownLatch inDialog = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UncaughtHandler handler = new UncaughtHandler(failure -> {
            shown.add(failure);
            inDialog.countDown();
            try {
                release.await(10, TimeUnit.SECONDS); // the "dialog" stays open until released
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // From a background thread: hops onto the JavaFX thread.
        handler.uncaughtException(new Thread("worker"), new IllegalStateException("first"));
        assertTrue(inDialog.await(10, TimeUnit.SECONDS));
        assertTrue(handler.isShowing());
        // A second one while the dialog is open is only logged.
        handler.uncaughtException(new Thread("worker2"), new IllegalStateException("second"));
        release.countDown();
        WizardNavigationTest.onFxThread(() -> { }); // let the display runnable finish

        assertEquals(1, shown.size());
        assertEquals(ErrorCode.GENERAL, shown.get(0).code());
        assertEquals("java.lang.IllegalStateException: first", shown.get(0).getMessage());
        assertFalse(handler.isShowing());

        // After the dialog closed the next failure shows again.
        CountDownLatch again = new CountDownLatch(1);
        UncaughtHandler second = new UncaughtHandler(f -> {
            shown.add(f);
            again.countDown();
        });
        second.uncaughtException(Thread.currentThread(), new InstallerException(ErrorCode.PERMISSION_DENIED, "denied", "/x"));
        assertTrue(again.await(10, TimeUnit.SECONDS));
        assertEquals(ErrorCode.PERMISSION_DENIED, shown.get(1).code(), "installer exceptions pass through unwrapped");
    }

    @Test
    void cancellationIsNotAnError() {
        List<InstallerException> shown = new ArrayList<>();
        UncaughtHandler handler = new UncaughtHandler(shown::add);
        handler.uncaughtException(Thread.currentThread(), new CancelledException());
        assertTrue(shown.isEmpty());
        assertFalse(handler.isShowing());
    }
}
