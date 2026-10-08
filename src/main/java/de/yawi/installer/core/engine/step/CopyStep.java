package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.integrity.PathGuard;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.ResourceRef;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code copy}: a file or a whole directory tree. Attributes are copied,
 * which keeps the executable bit on Linux/macOS. The source may also be a
 * {@code classpath:} resource (single file). Existing files are replaced;
 * only files that did not exist are recorded as created.
 */
final class CopyStep extends AbstractFileStep {

    private final InstallStep.Copy step;

    CopyStep(InstallStep.Copy step) {
        super(step);
        this.step = step;
    }

    @Override
    void run(ExecutionContext context) throws IOException {
        Path to = target(context, step.to());
        String from = context.resolve(step.from());

        if (ResourceRef.isClasspath(from)) {
            copyResource(context, from, to);
            return;
        }
        Path source = Path.of(from);
        if (Files.isDirectory(source)) {
            copyTree(context, source, to);
        } else if (Files.isRegularFile(source)) {
            copyFile(context, source, to);
        } else {
            throw new StepFailedException(id(), "source " + source + " does not exist", null);
        }
    }

    private void copyResource(ExecutionContext context, String ref, Path to) throws IOException {
        if (to.getParent() != null) {
            createDirectories(context, to.getParent());
        }
        Prepared prepared = prepareTarget(context, to);
        try (InputStream in = ResourceRef.open(ref)) {
            Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
        }
        written(context, to, prepared);
        context.listener().stepProgress(1, to.toString());
    }

    private void copyFile(ExecutionContext context, Path source, Path to) throws IOException {
        if (to.getParent() != null) {
            createDirectories(context, to.getParent());
        }
        Prepared prepared = prepareTarget(context, to);
        Files.copy(source, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        written(context, to, prepared);
        context.listener().stepProgress(1, to.toString());
    }

    private void copyTree(ExecutionContext context, Path source, Path to) throws IOException {
        PathGuard targets = new PathGuard(to);
        long total;
        try (var stream = Files.walk(source)) {
            total = stream.filter(Files::isRegularFile).count();
        }
        AtomicLong copied = new AtomicLong();
        createDirectories(context, to);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                context.cancellation().checkpoint();
                createDirectories(context, to.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                context.cancellation().checkpoint();
                Path target = targets.confine(to.resolve(source.relativize(file).toString()));
                Prepared prepared = prepareTarget(context, target);
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                written(context, target, prepared);
                long n = copied.incrementAndGet();
                context.listener().stepProgress(total == 0 ? 1 : (double) n / total, target.toString());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public String describe(ExecutionContext context) {
        return "copy " + context.resolve(step.from()) + " -> " + context.resolve(step.to());
    }
}
