package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.ExecutableStep;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.ProgressListener;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.manifest.Command;
import de.yawi.installer.core.manifest.InstallStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * {@code run-command}: the platform's {@code <commands os>} one after the
 * other, each as an argument list through {@link ProcessBuilder} - never a
 * shell line, so a placeholder that expands to a path with spaces is still
 * one argument.
 *
 * <p>Output is read continuously on a reader thread (a full pipe buffer
 * would block the process), streamed to the listener and the log, and the
 * last {@link #TAIL_LINES} lines go into the failure's detail. A timeout or
 * a cancel kills the process together with its descendants.
 */
final class RunCommandStep implements ExecutableStep {

    private static final Logger LOG = LoggerFactory.getLogger(RunCommandStep.class);

    /** How much output a failure carries into the detail pane. */
    static final int TAIL_LINES = 200;
    /** How long a killed process may take to die before we stop waiting. */
    private static final long KILL_GRACE_SECONDS = 5;

    private final InstallStep.RunCommand step;

    RunCommandStep(InstallStep.RunCommand step) {
        this.step = step;
    }

    @Override
    public InstallStep definition() {
        return step;
    }

    @Override
    public void execute(ExecutionContext context) {
        List<Command> commands = step.commandsFor(context.platform().os());
        if (step.elevated() && !context.platform().isElevated()
                && !de.yawi.installer.core.elevation.ElevatedWorker.isChild()) {
            // Normally the step reached an elevated process (E12); without one it runs with the caller's rights.
            LOG.warn("Step {} is marked elevated but runs without elevation", step.id());
        }
        Path workingDir = workingDir(context);
        int n = 0;
        for (Command command : commands) {
            context.cancellation().checkpoint();
            List<String> args = command.args().stream().map(context::resolve).toList();
            context.listener().stepProgress(commands.size() == 1 ? -1 : (double) n / commands.size(), args.get(0));
            run(context, args, workingDir);
            n++;
        }
        context.listener().stepProgress(1, null);
    }

    private Path workingDir(ExecutionContext context) {
        return step.workingDirOrEmpty().map(dir -> {
            Path path = Path.of(context.resolve(dir));
            if (!Files.isDirectory(path)) {
                throw new StepFailedException(step.id(), "working directory " + path + " does not exist", null);
            }
            return path;
        }).orElse(null);
    }

    private void run(ExecutionContext context, List<String> args, Path workingDir) {
        ProcessBuilder builder = new ProcessBuilder(args).redirectErrorStream(true);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        step.env().forEach((name, value) -> builder.environment().put(name, context.resolve(value)));
        String display = String.join(" ", args);
        LOG.info("Step {}: running {}", step.id(), display);
        context.listener().output("$ " + display);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new StepFailedException(step.id(), "cannot start " + args.get(0) + ": " + e.getMessage(), e);
        }

        Tail tail = new Tail(TAIL_LINES);
        tail.add("$ " + display);
        Thread reader = startReader(process, context.listener(), tail);
        Runnable unhook = context.cancellation().onCancel(() -> destroyTree(process));
        try {
            boolean finished = step.timeoutSeconds() > 0
                    ? process.waitFor(step.timeoutSeconds(), TimeUnit.SECONDS)
                    : waitIndefinitely(process);
            if (!finished) {
                destroyTree(process);
                reader.join(TimeUnit.SECONDS.toMillis(KILL_GRACE_SECONDS));
                // The reason is one short line for the user; the command line is in the detail.
                throw new StepFailedException(step.id(), "timeout after " + step.timeoutSeconds() + " s",
                        StepFailedException.NO_EXIT_STATUS, tail.text(), null);
            }
            reader.join();
            if (context.cancellation().isCancelled()) {
                throw new CancelledException("cancelled while running " + display, null);
            }
            int status = process.exitValue();
            if (status != step.expectExitCode()) {
                throw new StepFailedException(step.id(), "exit code " + status + " (expected " + step.expectExitCode() + ")",
                        status, tail.text(), null);
            }
            LOG.debug("Step {}: {} finished with {}", step.id(), args.get(0), status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyTree(process);
            throw new CancelledException("interrupted while running " + display, e);
        } finally {
            unhook.run();
        }
    }

    private static boolean waitIndefinitely(Process process) throws InterruptedException {
        process.waitFor();
        return true;
    }

    private Thread startReader(Process process, ProgressListener listener, Tail tail) {
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    tail.add(line);
                    LOG.debug("[{}] {}", step.id(), line);
                    listener.output(line);
                }
            } catch (IOException e) {
                LOG.debug("Step {}: output ended: {}", step.id(), e.toString());
            }
        }, "step-" + step.id() + "-output");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    /** Kills the process and everything it spawned; descendants first, so nothing gets re-parented and lost. */
    static void destroyTree(Process process) {
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String describe(ExecutionContext context) {
        List<Command> commands = step.commandsFor(context.platform().os());
        String joined = commands.stream()
                .map(c -> c.args().stream().map(context::resolve).collect(Collectors.joining(" ")))
                .collect(Collectors.joining("; "));
        return "run " + joined + step.workingDirOrEmpty().map(d -> " (in " + context.resolve(d) + ")").orElse("");
    }

    /** The last N lines of output, for the failure detail. */
    static final class Tail {
        private final int limit;
        private final Deque<String> lines = new ArrayDeque<>();
        private boolean truncated;

        Tail(int limit) {
            this.limit = limit;
        }

        synchronized void add(String line) {
            if (lines.size() == limit) {
                lines.removeFirst();
                truncated = true;
            }
            lines.addLast(line);
        }

        synchronized String text() {
            if (lines.isEmpty()) {
                return null;
            }
            return (truncated ? "[...]\n" : "") + String.join("\n", lines);
        }
    }
}
