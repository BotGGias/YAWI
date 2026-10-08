package de.yawi.installer.ui;

import de.yawi.installer.core.answers.AnswerFile;
import de.yawi.installer.core.answers.AnswerFileWriter;
import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.state.InstallationRecord;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * "Save answers" on the finish page: the choices the user made
 * in the wizard as an answer file, so the same installation can be rolled
 * out with {@code --silent --config=<file>}. Inputs that look like secrets
 * are left out and named in a comment - a plain file is no place for a
 * password.
 */
public final class AnswerExport {

    private AnswerExport() {
    }

    /** The user's raw component selection, in manifest order; dependencies are resolved again on replay. */
    public static AnswerFile of(InstallerModel model) {
        InstallManifest manifest = model.getManifest();
        List<String> components = manifest.components().stream().map(Component::id)
                .filter(model.getSelectedComponents()::contains).toList();
        Set<String> selected = manifest.resolveSelection(components);
        Map<String, de.yawi.installer.core.download.ProviderKind> sources = new LinkedHashMap<>();
        manifest.sourcesFor(selected, model.getPlatform().os()).forEach(s -> sources.put(s.id(), model.sourceChoice(s)));
        Optional<Boolean> associate = manifest.integration().defaultAssociationExtensions().isEmpty()
                ? Optional.empty() : Optional.of(model.isAssociateFileTypes());
        Optional<Boolean> addPath = manifest.integration().pathEntries().isEmpty()
                ? Optional.empty() : Optional.of(model.isAddPathEntries());
        return new AnswerFile(Optional.of(manifest.product().id()), Optional.of(model.getLocale().toLanguageTag()),
                Optional.of(model.getEffectiveDestination().toString()), Optional.of(model.isSystemWide()),
                Optional.of(components), sources, InstallationRecord.withoutSecrets(inputs(model, selected), labels(manifest)),
                manifest.wizard().licenseOrEmpty().isPresent(), associate, addPath);
    }

    /** The ids {@link #of} left out because they look like secrets. */
    public static List<String> omitted(InstallerModel model) {
        InstallManifest manifest = model.getManifest();
        Set<String> selected = manifest.resolveSelection(model.getSelectedComponents());
        Map<String, String> all = inputs(model, selected);
        Map<String, String> safe = InstallationRecord.withoutSecrets(all, labels(manifest));
        List<String> omitted = new ArrayList<>(all.keySet());
        omitted.removeAll(safe.keySet());
        return omitted;
    }

    /** The proposed file name: {@code <product-id>-answers.xml}. */
    public static String defaultFileName(InstallerModel model) {
        return model.getManifest().product().id() + "-answers.xml";
    }

    /**
     * Asks where to save and writes the file. Returns the file, or empty if
     * the user cancelled.
     *
     * @throws IOException if writing fails
     */
    public static Optional<Path> save(Window owner, InstallerModel model, String title) throws IOException {
        Optional<Path> chosen = Diagnostics.chooseSaveFile(owner, model.getPlatform(), title, defaultFileName(model),
                "XML", "*.xml");
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(AnswerFileWriter.write(of(model), omitted(model), chosen.get()));
    }

    /** Values of the selected components' inputs, in manifest order; only those the user actually has. */
    private static Map<String, String> inputs(InstallerModel model, Set<String> selected) {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (Component c : model.getManifest().components()) {
            if (selected.contains(c.id())) {
                c.inputs().forEach(i -> {
                    String value = model.getInputs().get(i.id());
                    if (value != null) {
                        inputs.put(i.id(), value);
                    }
                });
            }
        }
        return inputs;
    }

    private static Map<String, String> labels(InstallManifest manifest) {
        Map<String, String> labels = new LinkedHashMap<>();
        manifest.components().forEach(c -> c.inputs().forEach(i -> labels.put(i.id(), i.label() == null ? "" : i.label())));
        return labels;
    }
}
