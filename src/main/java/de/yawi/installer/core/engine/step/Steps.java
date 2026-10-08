package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.ExecutableStep;
import de.yawi.installer.core.engine.StepFactory;
import de.yawi.installer.core.manifest.InstallStep;

/** The default {@link StepFactory}: one executable class per manifest step type. */
public final class Steps implements StepFactory {

    public static final Steps DEFAULT = new Steps();

    @Override
    public ExecutableStep create(InstallStep definition) {
        return switch (definition) {
            case InstallStep.Extract s -> new ExtractStep(s);
            case InstallStep.Copy s -> new CopyStep(s);
            case InstallStep.Template s -> new TemplateStep(s);
            case InstallStep.Mkdir s -> new MkdirStep(s);
            case InstallStep.Chmod s -> new ChmodStep(s);
            case InstallStep.RunCommand s -> new RunCommandStep(s);
            case InstallStep.Shortcut s -> new ShortcutStep(s);
            case InstallStep.FileAssociation s -> new AssociationStep(s);
            case InstallStep.PathEntries s -> new PathStep(s);
        };
    }
}
