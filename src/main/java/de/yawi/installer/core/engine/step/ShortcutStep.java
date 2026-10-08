package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.integrity.PathEscapeException;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.integration.DesktopDatabase;
import de.yawi.installer.core.integration.DesktopEntry;
import de.yawi.installer.core.integration.PngHeader;
import de.yawi.installer.core.integration.ShortcutLayout;
import de.yawi.installer.core.integration.ShortcutSpec;
import de.yawi.installer.core.integration.WindowsShortcutScript;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.IntegrationConfig;
import de.yawi.installer.core.manifest.ResourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@code shortcut}: a {@code <shortcut>} of the manifest's
 * {@code <integration>} block, placed where this platform keeps launchers
 * (see {@link ShortcutLayout}). The target must lie inside the destination;
 * the icon comes from the shortcut's {@code <icon>} or, failing that, the
 * product icon. Files outside the destination are recorded with absolute
 * paths - existing ones are copied to the backup first, so a rollback puts
 * the previous shortcut back - and the uninstaller removes them like any
 * other recorded file. A failure here never aborts the installation.
 */
final class ShortcutStep extends AbstractFileStep {

    private static final Logger LOG = LoggerFactory.getLogger(ShortcutStep.class);
    /** How long the PowerShell that writes a {@code .lnk} may take. */
    static final Duration POWERSHELL_TIMEOUT = Duration.ofSeconds(30);

    private final InstallStep.Shortcut step;

    ShortcutStep(InstallStep.Shortcut step) {
        super(step);
        this.step = step;
    }

    @Override
    void run(ExecutionContext context) throws IOException {
        IntegrationConfig.Shortcut shortcut = step.shortcut();
        Path target = confined(context, shortcut.target(), "target");
        if (!Files.exists(target)) {
            throw new StepFailedException(id(), "shortcut target " + target + " does not exist", null);
        }
        Optional<String> iconRef = iconRef(context);
        Optional<Path> iconFile = iconRef.filter(ref -> !ResourceRef.isClasspath(ref)).map(Path::of);
        ShortcutSpec spec = new ShortcutSpec(context.manifest().product().id(), shortcut.id(),
                context.resolve(shortcut.name()), target, context.destination(), iconFile,
                shortcut.desktop(), shortcut.menu());
        // The scope follows the destination, like the maintenance page's prefill (E14-S02).
        boolean systemWide = context.platform().isSystemPath(context.destination());
        ShortcutLayout.Placement placement = ShortcutLayout.of(context.platform(), systemWide, spec);

        switch (context.platform().os()) {
            case LINUX -> linux(context, spec, placement, iconRef);
            case WINDOWS -> windows(context, spec, placement);
            case MACOS -> mac(context, spec, placement);
            case UNKNOWN -> throw new StepFailedException(id(), "unsupported platform", null);
        }
    }

    // --- Linux: .desktop files and a hicolor icon ------------------------------

    private void linux(ExecutionContext context, ShortcutSpec spec, ShortcutLayout.Placement placement,
                       Optional<String> iconRef) throws IOException {
        Optional<String> iconName = Optional.empty();
        if (iconRef.isPresent() && placement.iconDir().isPresent()) {
            byte[] bytes;
            try (InputStream in = ResourceRef.open(iconRef.get())) {
                bytes = in.readAllBytes();
            }
            Optional<int[]> size = PngHeader.size(bytes);
            if (size.isPresent()) {
                Path icon = ShortcutLayout.iconFile(placement.iconDir().get(), spec, size.get()[0], size.get()[1]);
                writeExternal(context, icon, bytes);
                iconName = Optional.of(ShortcutLayout.baseName(spec));
            } else {
                context.listener().output("shortcut: icon " + iconRef.get() + " is not a PNG, the entry gets no icon");
            }
        }
        byte[] entry = DesktopEntry.render(spec, iconName).getBytes(StandardCharsets.UTF_8);
        if (placement.menuFile().isPresent()) {
            writeExternal(context, placement.menuFile().get(), entry);
            DesktopDatabase.refresh(context.platform(), placement.menuFile().get().getParent());
        }
        if (placement.desktopFile().isPresent()) {
            writeExternal(context, placement.desktopFile().get(), entry);
            // Desktops only trust an executable launcher file.
            context.platform().makeExecutable(placement.desktopFile().get());
        }
    }

    // --- Windows: .lnk through PowerShell ---------------------------------------

    private void windows(ExecutionContext context, ShortcutSpec spec, ShortcutLayout.Placement placement)
            throws IOException {
        Optional<Path> ico = spec.iconFile().filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".ico"));
        for (Path lnk : files(placement)) {
            createDirectories(context, lnk.getParent());
            boolean existed = prepareExternal(context, lnk);
            String script = WindowsShortcutScript.render(lnk, spec.target(), spec.workingDir(), ico);
            runPowerShell(script);
            if (!Files.exists(lnk)) {
                throw new StepFailedException(id(), "PowerShell reported success but " + lnk + " was not created", null);
            }
            if (!existed) {
                context.record().fileCreated(lnk);
            }
            context.listener().stepProgress(1, lnk.toString());
        }
    }

    private void runPowerShell(String script) throws IOException {
        Process process = new ProcessBuilder(WindowsShortcutScript.command(script))
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        try {
            if (!process.waitFor(POWERSHELL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new StepFailedException(id(), "PowerShell did not finish within "
                        + POWERSHELL_TIMEOUT.toSeconds() + " s", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new StepFailedException(id(), "interrupted while creating the shortcut", e);
        }
        if (process.exitValue() != 0) {
            throw new StepFailedException(id(), "PowerShell exited with " + process.exitValue()
                    + (output.isEmpty() ? "" : ": " + output), null);
        }
    }

    // --- macOS: symbolic links ---------------------------------------------------

    private void mac(ExecutionContext context, ShortcutSpec spec, ShortcutLayout.Placement placement)
            throws IOException {
        Path linkTarget = ShortcutLayout.appBundleOf(spec.target()).orElse(spec.target());
        for (Path link : files(placement)) {
            createDirectories(context, link.getParent());
            boolean existed = prepareExternal(context, link);
            if (existed) {
                if (Files.isDirectory(link, LinkOption.NOFOLLOW_LINKS)) {
                    throw new StepFailedException(id(), link + " exists and is a folder, not replaced", null);
                }
                Files.delete(link);
            }
            Files.createSymbolicLink(link, linkTarget);
            if (!existed) {
                context.record().fileCreated(link);
            }
            context.listener().stepProgress(1, link.toString());
        }
    }

    // --- shared ------------------------------------------------------------------

    private static List<Path> files(ShortcutLayout.Placement placement) {
        List<Path> files = new ArrayList<>();
        placement.menuFile().ifPresent(files::add);
        placement.desktopFile().ifPresent(files::add);
        return files;
    }

    /** A manifest path resolved and confined to the destination; an escape is this step's failure. */
    private Path confined(ExecutionContext context, String manifestPath, String what) {
        try {
            return guard(context).confine(Path.of(context.resolve(manifestPath)));
        } catch (PathEscapeException e) {
            throw new StepFailedException(id(), "shortcut " + what + " must lie inside the destination: "
                    + e.getMessage(), e);
        }
    }

    /**
     * The icon to use: the shortcut's {@code <icon>} if it can be read (a
     * classpath resource or a file inside the destination), else the product
     * icon, else nothing.
     */
    private Optional<String> iconRef(ExecutionContext context) {
        Optional<String> own = step.shortcut().iconOrEmpty().map(context::resolve);
        if (own.isPresent()) {
            if (readable(context, own.get())) {
                return own;
            }
            context.listener().output("shortcut: icon " + own.get() + " is not available, using the product icon");
        }
        return context.manifest().product().iconOrEmpty().filter(ref -> readable(context, ref));
    }

    private boolean readable(ExecutionContext context, String ref) {
        if (ResourceRef.isClasspath(ref)) {
            try (InputStream in = ResourceRef.open(ref)) {
                return in != null;
            } catch (IOException e) {
                return false;
            }
        }
        try {
            return Files.isRegularFile(guard(context).confine(Path.of(ref)));
        } catch (PathEscapeException e) {
            LOG.warn("Step {}: icon {} ignored: {}", id(), ref, e.getMessage());
            return false;
        }
    }

    @Override
    public String describe(ExecutionContext context) {
        IntegrationConfig.Shortcut shortcut = step.shortcut();
        Path target = Path.of(context.resolve(shortcut.target())).toAbsolutePath().normalize();
        ShortcutSpec spec = new ShortcutSpec(context.manifest().product().id(), shortcut.id(),
                context.resolve(shortcut.name()), target, context.destination(), Optional.empty(),
                shortcut.desktop(), shortcut.menu());
        ShortcutLayout.Placement placement = ShortcutLayout.of(context.platform(),
                context.platform().isSystemPath(context.destination()), spec);
        List<Path> files = files(placement);
        return "shortcut \"" + spec.name() + "\" -> " + target + (files.isEmpty() ? " (neither desktop nor menu)"
                : " as " + files.stream().map(Path::toString).reduce((a, b) -> a + ", " + b).orElse(""));
    }
}
