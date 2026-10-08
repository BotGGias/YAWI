package de.yawi.installer.cli;

import de.yawi.installer.core.error.WaitTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code --wait-pid}: the caller (the application being updated) is still
 * shutting down when the installer starts; nothing may be written before it
 * is gone (update contract). A PID that does not exist counts as ended.
 */
public final class ProcessWaiter {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessWaiter.class);

    /** The contract's grace period; {@code -Dyawi.waitTimeoutSeconds} overrides it for tests. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private ProcessWaiter() {
    }

    public static Duration configuredTimeout() {
        String override = System.getProperty("yawi.waitTimeoutSeconds");
        if (override != null && !override.isBlank()) {
            try {
                return Duration.ofSeconds(Long.parseLong(override.strip()));
            } catch (NumberFormatException e) {
                LOG.warn("Ignoring yawi.waitTimeoutSeconds={}", override);
            }
        }
        return DEFAULT_TIMEOUT;
    }

    /**
     * Blocks until every process ended or the timeout is over.
     *
     * @throws WaitTimeoutException naming the processes still running
     */
    public static void awaitExit(List<Long> pids, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        List<Long> stillRunning = new ArrayList<>();
        for (long pid : pids) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                LOG.info("Process {} has already ended", pid);
                continue;
            }
            long remaining = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
            LOG.info("Waiting up to {} ms for process {} to end", remaining, pid);
            try {
                handle.get().onExit().get(remaining, TimeUnit.MILLISECONDS);
                LOG.info("Process {} ended", pid);
            } catch (TimeoutException e) {
                stillRunning.add(pid);
            } catch (ExecutionException e) {
                LOG.debug("onExit of {} failed, treating it as ended: {}", pid, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stillRunning.add(pid);
                break;
            }
        }
        if (!stillRunning.isEmpty()) {
            throw new WaitTimeoutException(stillRunning, timeout);
        }
    }
}
