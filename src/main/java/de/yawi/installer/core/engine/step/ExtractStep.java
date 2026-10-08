package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.integrity.PathGuard;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * {@code extract}: unpacks a source archive - ZIP or tar.gz - into a folder.
 * A source that is not an archive (a standalone {@code .exe} or
 * {@code .AppImage}) is placed as-is under {@code to} instead, executable bit
 * set on POSIX. Every entry path is confined by the
 * {@link de.yawi.installer.core.integrity.PathGuard} (zip slip, E10-S03-T02);
 * links inside tar archives are skipped. Progress is bytes read against
 * {@code source/@size} when the packager gave one.
 *
 * <p>ZIP carries no portable permission bits the JDK exposes, so files from
 * ZIP arrive without the executable bit; use a {@code chmod} step or tar.gz,
 * whose modes are applied.
 */
final class ExtractStep extends AbstractFileStep {

    private static final Logger LOG = LoggerFactory.getLogger(ExtractStep.class);
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final InstallStep.Extract step;

    ExtractStep(InstallStep.Extract step) {
        super(step);
        this.step = step;
    }

    @Override
    void run(ExecutionContext context) throws IOException {
        Source source = context.manifest().source(step.archiveRef()).orElseThrow(
                () -> new StepFailedException(id(), "source '" + step.archiveRef() + "' is not defined", null));
        Path to = target(context, step.to());
        createDirectories(context, to);
        String name = context.artifacts().describe(source);

        try (CountingInputStream counting = new CountingInputStream(new BufferedInputStream(
                context.artifacts().open(source)), source.sizeBytes())) {
            Format format = Format.detect(name, counting);
            LOG.info("Step {}: extracting {} ({}) to {}", id(), name, format, to);
            switch (format) {
                case ZIP -> extractZip(context, counting, to);
                case TAR_GZ -> extractTar(context, new GZIPInputStream(counting), to);
                case RAW -> extractRaw(context, name, counting, to);
            }
        }
        context.listener().stepProgress(1, to.toString());
    }

    /** Not an archive: the source is a single standalone executable (e.g. .exe, .AppImage). */
    private void extractRaw(ExecutionContext context, String name, InputStream in, Path to) throws IOException {
        String fileName = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        Path target = to.resolve(fileName);
        writeFile(context, in, target);
        if (POSIX) {
            Files.setPosixFilePermissions(target, ChmodStep.permissions("755"));
        }
    }

    private void extractZip(ExecutionContext context, CountingInputStream counting, Path to) throws IOException {
        // Entries must stay inside the extraction target, not just inside the destination.
        PathGuard entries = new PathGuard(to);
        ZipInputStream zip = new ZipInputStream(counting, StandardCharsets.UTF_8);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            context.cancellation().checkpoint();
            Path target = entries.confine(to, entry.getName());
            if (entry.isDirectory()) {
                createDirectories(context, target);
            } else {
                writeFile(context, zip, target);
            }
            context.listener().stepProgress(counting.fraction(), entry.getName());
            zip.closeEntry();
        }
    }

    private void extractTar(ExecutionContext context, InputStream gunzipped, Path to) throws IOException {
        PathGuard entries = new PathGuard(to);
        TarInput tar = new TarInput(gunzipped);
        TarInput.Entry entry;
        while ((entry = tar.next()) != null) {
            context.cancellation().checkpoint();
            switch (entry.type()) {
                case SYMLINK, HARDLINK, OTHER -> {
                    LOG.warn("Step {}: skipping tar entry {} of type {}", id(), entry.name(), entry.type());
                    context.listener().output("skipped " + entry.name() + " (" + entry.type().name().toLowerCase(Locale.ROOT) + ")");
                    continue;
                }
                default -> { }
            }
            Path target = entries.confine(to, entry.name());
            if (entry.isDirectory()) {
                createDirectories(context, target);
            } else {
                writeFile(context, tar.content(), target);
                if (POSIX && entry.mode() != 0) {
                    // Owner must keep read/write, whatever the archive says.
                    Files.setPosixFilePermissions(target, ChmodStep.permissions(Integer.toOctalString((entry.mode() & 0777) | 0600)));
                }
            }
            context.listener().stepProgress(-1, entry.name());
        }
    }

    private static void writeFile(ExecutionContext context, InputStream content, Path target) throws IOException {
        if (target.getParent() != null) {
            createDirectories(context, target.getParent());
        }
        Prepared prepared = prepareTarget(context, target);
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        written(context, target, prepared);
    }

    @Override
    public String describe(ExecutionContext context) {
        return "extract " + step.archiveRef() + " -> " + context.resolve(step.to());
    }

    enum Format {
        ZIP, TAR_GZ, RAW;

        /** By file name first, by magic bytes if the name does not tell. */
        static Format detect(String name, BufferedInputStream in) throws IOException {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip")) {
                return ZIP;
            }
            if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
                return TAR_GZ;
            }
            if (lower.endsWith(".exe") || lower.endsWith(".appimage")) {
                return RAW;
            }
            in.mark(4);
            byte[] head = in.readNBytes(4);
            in.reset();
            if (TarInput.looksLikeZip(head)) {
                return ZIP;
            }
            if (TarInput.looksLikeGzip(head)) {
                return TAR_GZ;
            }
            return RAW;
        }
    }

    /** Counts bytes for the progress fraction; closes the underlying stream. */
    static final class CountingInputStream extends BufferedInputStream {
        private final long expected;
        private long count;

        CountingInputStream(InputStream in, long expected) {
            super(in);
            this.expected = expected;
        }

        @Override
        public synchronized int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public synchronized int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        /** 0..1 against the expected size, or -1 if the size is unknown. */
        double fraction() {
            return expected <= 0 ? -1 : Math.min(1.0, (double) count / expected);
        }
    }
}
