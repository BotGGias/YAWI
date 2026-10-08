package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.integration.AssociationLayout;
import de.yawi.installer.core.integration.AssociationSpec;
import de.yawi.installer.core.integration.DesktopDatabase;
import de.yawi.installer.core.integration.DesktopEntry;
import de.yawi.installer.core.integration.InfoPlist;
import de.yawi.installer.core.integration.MimeDatabase;
import de.yawi.installer.core.integration.MimePackage;
import de.yawi.installer.core.integration.ShortcutLayout;
import de.yawi.installer.core.integration.WindowsAssociationScript;
import de.yawi.installer.core.integrity.PathEscapeException;
import de.yawi.installer.core.manifest.IntegrationConfig;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.platform.PathLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@code fileAssociation}: a {@code <fileAssociation>} of the
 * manifest's {@code <integration>} block, so files of the given extension open
 * the installed application by double-click. Linux uses a shared-mime-info
 * type, a hidden handler {@code .desktop} and {@code xdg-mime}; Windows the
 * registry under {@code Software\Classes}; macOS the bundle's document types.
 * The target must lie inside the destination. Every file this writes is
 * recorded, so the uninstaller removes it; the registry keys and the
 * {@code mimeapps.list} default are reversed from the manifest (see the
 * {@code Uninstaller}). A failure here never aborts the installation.
 */
final class AssociationStep extends AbstractFileStep {

    private static final Logger LOG = LoggerFactory.getLogger(AssociationStep.class);
    /** How long a helper tool ({@code reg}, {@code xdg-mime}, {@code lsregister}) may take. */
    static final Duration TOOL_TIMEOUT = Duration.ofSeconds(30);

    private final InstallStep.FileAssociation step;

    AssociationStep(InstallStep.FileAssociation step) {
        super(step);
        this.step = step;
    }

    @Override
    void run(ExecutionContext context) throws IOException {
        IntegrationConfig.FileAssociation assoc = step.association();
        Path target = confined(context, assoc.target());
        if (!Files.exists(target)) {
            throw new StepFailedException(id(), "association target " + target + " does not exist", null);
        }
        AssociationSpec spec = new AssociationSpec(context.manifest().product().id(), assoc.extension(),
                target, context.destination(), assoc.descriptionOrEmpty());
        boolean systemWide = context.platform().isSystemPath(context.destination());
        switch (context.platform().os()) {
            case LINUX -> linux(context, spec, systemWide);
            case WINDOWS -> windows(context, spec, systemWide);
            case MACOS -> mac(context, spec);
            case UNKNOWN -> throw new StepFailedException(id(), "unsupported platform", null);
        }
    }

    // --- Linux: shared-mime-info + handler .desktop + xdg-mime -------------------

    private void linux(ExecutionContext context, AssociationSpec spec, boolean systemWide) throws IOException {
        Path pkg = AssociationLayout.mimePackage(context.platform(), systemWide, spec);
        writeExternal(context, pkg, MimePackage.render(spec).getBytes(StandardCharsets.UTF_8));
        MimeDatabase.refresh(context.platform(), AssociationLayout.mimeDir(context.platform(), systemWide));

        Path handler = AssociationLayout.handlerDesktop(context.platform(), systemWide, spec);
        writeExternal(context, handler, DesktopEntry.renderHandler(spec).getBytes(StandardCharsets.UTF_8));
        // Desktops only trust an executable launcher file.
        context.platform().makeExecutable(handler);
        DesktopDatabase.refresh(context.platform(), handler.getParent());

        xdgMimeDefault(context, spec);
    }

    private void xdgMimeDefault(ExecutionContext context, AssociationSpec spec) {
        Optional<Path> tool = PathLookup.find(context.platform(), "xdg-mime");
        if (tool.isEmpty()) {
            context.listener().output("association: xdg-mime not found, " + spec.extension()
                    + " is registered but not set as the default");
            return;
        }
        ProcessBuilder pb = new ProcessBuilder(tool.get().toString(), "default",
                AssociationLayout.handlerDesktopName(spec), spec.mimeType());
        // xdg-mime writes the default into $XDG_CONFIG_HOME/mimeapps.list; point it at the platform's
        // home so a per-user install (and any test with a scratch home) touches only that home.
        java.util.Map<String, String> env = pb.environment();
        env.put("HOME", context.platform().homeDir().toString());
        env.put("XDG_CONFIG_HOME",
                context.platform().configDir(context.manifest().product().id()).getParent().toString());
        env.put("XDG_DATA_HOME", AssociationLayout.mimeDir(context.platform(), false).getParent().toString());
        runBestEffort(pb, "xdg-mime default " + spec.mimeType());
    }

    // --- Windows: registry under Software\Classes -------------------------------

    private void windows(ExecutionContext context, AssociationSpec spec, boolean systemWide) {
        for (List<String> command : WindowsAssociationScript.addCommands(spec, systemWide)) {
            runOrFail(command);
        }
        context.listener().stepProgress(1, spec.extension() + " -> " + spec.target());
    }

    // --- macOS: CFBundleDocumentTypes in the bundle -----------------------------

    private void mac(ExecutionContext context, AssociationSpec spec) throws IOException {
        Optional<Path> bundle = ShortcutLayout.appBundleOf(spec.target());
        if (bundle.isEmpty()) {
            context.listener().output("association: " + spec.target()
                    + " is not inside an .app bundle, no macOS association");
            return;
        }
        Path plist = bundle.get().resolve("Contents").resolve("Info.plist");
        if (!Files.isRegularFile(plist)) {
            context.listener().output("association: " + plist + " does not exist, no macOS association");
            return;
        }
        String existing = Files.readString(plist, StandardCharsets.UTF_8);
        String merged = InfoPlist.merge(existing, spec);
        if (!merged.equals(existing)) {
            Files.writeString(plist, merged, StandardCharsets.UTF_8);
        }
        lsregister(bundle.get());
        context.listener().stepProgress(1, spec.extension() + " -> " + bundle.get());
    }

    private void lsregister(Path bundle) {
        Path tool = Path.of("/System/Library/Frameworks/CoreServices.framework/Frameworks/"
                + "LaunchServices.framework/Support/lsregister");
        if (!Files.isExecutable(tool)) {
            return;
        }
        runBestEffort(new ProcessBuilder(tool.toString(), "-f", bundle.toString()), "lsregister " + bundle);
    }

    // --- process helpers --------------------------------------------------------

    /** Runs a tool whose failure is only a warning (Linux/macOS best-effort registration). */
    private void runBestEffort(ProcessBuilder builder, String what) {
        try {
            Process process = builder.redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(TOOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                LOG.warn("{} did not finish within {} s", what, TOOL_TIMEOUT.toSeconds());
            } else if (process.exitValue() != 0) {
                LOG.warn("{} exited with {}", what, process.exitValue());
            }
        } catch (IOException e) {
            LOG.warn("{} could not be run: {}", what, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs a {@code reg} command whose failure is this step's failure (the engine maps it to continue). */
    private void runOrFail(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            if (!process.waitFor(TOOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new StepFailedException(id(), String.join(" ", command) + " did not finish within "
                        + TOOL_TIMEOUT.toSeconds() + " s", null);
            }
            if (process.exitValue() != 0) {
                throw new StepFailedException(id(), String.join(" ", command) + " exited with "
                        + process.exitValue() + (output.isEmpty() ? "" : ": " + output), null);
            }
        } catch (IOException e) {
            throw new StepFailedException(id(), String.join(" ", command) + " could not be run: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailedException(id(), "interrupted while registering the association", e);
        }
    }

    /** A manifest path resolved and confined to the destination; an escape is this step's failure. */
    private Path confined(ExecutionContext context, String manifestPath) {
        try {
            return guard(context).confine(Path.of(context.resolve(manifestPath)));
        } catch (PathEscapeException e) {
            throw new StepFailedException(id(), "association target must lie inside the destination: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public String describe(ExecutionContext context) {
        IntegrationConfig.FileAssociation assoc = step.association();
        String mimeType = AssociationSpec.mimeType(context.manifest().product().id(), assoc.extension());
        Path target = Path.of(context.resolve(assoc.target())).toAbsolutePath().normalize();
        return "associate " + assoc.extension() + " (" + mimeType + ") with " + target;
    }
}
