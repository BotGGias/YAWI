package de.yawi.installer.core.engine;

import de.yawi.installer.core.error.CancelledException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The user's Cancel, as the engine sees it. Steps call {@link #checkpoint()}
 * at safe points; long running ones register a hook that stops what they are
 * doing (kill the process, close the stream) so the checkpoint is reached
 * soon after.
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> hooks = new ArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** Idempotent; runs every registered hook once, on the caller's thread. */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        List<Runnable> snapshot;
        synchronized (hooks) {
            snapshot = List.copyOf(hooks);
            hooks.clear();
        }
        snapshot.forEach(Runnable::run);
    }

    /** @throws CancelledException if {@link #cancel()} was called */
    public void checkpoint() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    /**
     * Registers what to do on cancel; runs immediately if already cancelled.
     * Returns a runnable that removes the hook again once the work is done.
     */
    public Runnable onCancel(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        synchronized (hooks) {
            if (!cancelled.get()) {
                hooks.add(hook);
                return () -> {
                    synchronized (hooks) {
                        hooks.remove(hook);
                    }
                };
            }
        }
        hook.run();
        return () -> { };
    }
}
