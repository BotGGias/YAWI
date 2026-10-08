package de.yawi.installer.ui.page;

import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.download.Version;
import de.yawi.installer.core.download.VersionChecker;
import de.yawi.installer.core.manifest.ByteSize;
import de.yawi.installer.core.manifest.Provider;
import de.yawi.installer.core.manifest.Source;
import de.yawi.installer.ui.InstallerModel;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Source page: for every source the selected components need and
 * that can be obtained in more than one way, "use the shipped data" or
 * "download". Skipped when there is nothing to choose. On entry the
 * {@code <versionCheck>} URLs are asked in the background; a newer version
 * online is shown and preselects the download unless the user chose already.
 */
public class SourcePageController extends WizardPageController {

    private static final Logger LOG = LoggerFactory.getLogger(SourcePageController.class);

    @FXML
    private Label hint;
    @FXML
    private VBox sources;

    private final VersionChecker checker;
    /** Sources the user clicked; the version check does not override those. */
    private final Set<String> chosenByUser = new HashSet<>();
    private final Map<String, Optional<String>> onlineVersions = new java.util.HashMap<>();
    /** Grows with every entry; a late answer for an older entry is dropped. */
    private int generation;
    /** Completes once every check started by the last entry has been applied on the JavaFX thread. */
    private CompletableFuture<Void> checksApplied = CompletableFuture.completedFuture(null);
    /** True while the page itself flips a radio button, so the listener does not count it as the user's choice. */
    private boolean applying;

    public SourcePageController(InstallerModel model) {
        this(model, new VersionChecker());
    }

    public SourcePageController(InstallerModel model, VersionChecker checker) {
        super(model);
        this.checker = checker;
    }

    /** Nothing to choose: every needed source has one usable way (torrent does not count until E08). */
    @Override
    public boolean isSkippable() {
        return choosable().isEmpty() || uninstalling();
    }

    /** The needed sources with more than one supported provider kind, manifest order. */
    List<Source> choosable() {
        Set<String> selected = model.getManifest().resolveSelection(model.getSelectedComponents());
        return model.getManifest().sourcesFor(selected, model.getPlatform().os()).stream()
                .filter(s -> s.providers().stream().map(ProviderKind::of).filter(ProviderKind::isSupported)
                        .distinct().count() > 1)
                .toList();
    }

    @Override
    public void onEnter() {
        generation++;
        int mine = generation;
        sources.getChildren().clear();
        List<CompletableFuture<Void>> pending = new java.util.ArrayList<>();
        for (Source source : choosable()) {
            sources.getChildren().add(block(source));
            source.versionCheckUrlOrEmpty().ifPresent(url -> {
                setVersionLine(source, Optional.empty(), true);
                CompletableFuture<Void> applied = new CompletableFuture<>();
                pending.add(applied);
                checker.check(url).thenAccept(version -> javafx.application.Platform.runLater(() -> {
                    if (mine == generation) {
                        onVersionKnown(source, version);
                    }
                    applied.complete(null);
                }));
            });
        }
        checksApplied = CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }

    private VBox block(Source source) {
        VBox box = new VBox(4);
        box.setId("source-" + source.id());
        Label title = new Label();
        title.getStyleClass().add("section-title");
        title.textProperty().bind(Bindings.createStringBinding(() -> source.sizeBytes() > 0
                        ? model.i18n().get("source.title.sized", source.id(), ByteSize.format(source.sizeBytes(), model.getLocale()))
                        : source.id(), model.localeProperty()));
        box.getChildren().add(title);

        ToggleGroup group = new ToggleGroup();
        Map<ProviderKind, RadioButton> buttons = new EnumMap<>(ProviderKind.class);
        for (Provider provider : source.providers()) {
            ProviderKind kind = ProviderKind.of(provider);
            if (buttons.containsKey(kind)) {
                continue;
            }
            RadioButton button = new RadioButton();
            button.setId("source-" + source.id() + "-" + kind.name().toLowerCase(java.util.Locale.ROOT));
            button.setToggleGroup(group);
            button.setMnemonicParsing(false);
            button.textProperty().bind(model.i18n().text("source.kind." + kind.name().toLowerCase(java.util.Locale.ROOT)));
            button.setDisable(!kind.isSupported());
            button.setUserData(kind);
            buttons.put(kind, button);
            box.getChildren().add(button);
        }
        RadioButton current = buttons.get(model.sourceChoice(source));
        if (current != null) {
            current.setSelected(true);
        }
        // E08: a torrent client opens a port and uploads to others - say so where it is chosen.
        source.providers().stream().filter(Provider.Torrent.class::isInstance).map(Provider.Torrent.class::cast)
                .findFirst().ifPresent(torrent -> {
                    Label hint = new Label();
                    hint.setId("torrent-hint-" + source.id());
                    hint.getStyleClass().add("page-hint");
                    hint.setWrapText(true);
                    hint.textProperty().bind(model.i18n().text("source.torrent.hint", String.valueOf(torrent.port())));
                    box.getChildren().add(hint);
                });
        // E10-S01: a download without a checksum in the manifest cannot be verified - say so where it is chosen.
        if (source.sha256() == null && source.providers().stream().anyMatch(p -> !(p instanceof Provider.Bundled))) {
            Label unverified = new Label();
            unverified.setId("unverified-" + source.id());
            unverified.getStyleClass().addAll("page-hint", "warning");
            unverified.setWrapText(true);
            unverified.textProperty().bind(model.i18n().text("source.unverified"));
            box.getChildren().add(unverified);
        }
        group.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                group.selectToggle(old);
                return;
            }
            ProviderKind kind = (ProviderKind) toggle.getUserData();
            if (!applying) {
                chosenByUser.add(source.id());
            }
            if (model.sourceChoice(source) != kind) {
                LOG.info("Source {}: user chose {}", source.id(), kind);
                model.getSourceChoices().put(source.id(), kind);
            }
        });

        Label version = new Label();
        version.setId("version-" + source.id());
        version.getStyleClass().add("page-hint");
        version.setWrapText(true);
        version.setVisible(false);
        version.managedProperty().bind(version.visibleProperty());
        box.getChildren().add(version);
        return box;
    }

    private void onVersionKnown(Source source, Optional<String> online) {
        onlineVersions.put(source.id(), online);
        setVersionLine(source, online, false);
        String current = model.getManifest().product().version();
        if (online.isPresent() && Version.isNewer(online.get(), current) && !chosenByUser.contains(source.id())
                && source.hasProviderOfType(Provider.Http.class)) {
            LOG.info("Source {}: online version {} is newer than {}, preselecting download", source.id(), online.get(), current);
            model.getSourceChoices().put(source.id(), ProviderKind.HTTP);
            RadioButton download = (RadioButton) sources.lookup("#source-" + source.id() + "-http");
            if (download != null) {
                applying = true;
                try {
                    download.setSelected(true);
                } finally {
                    applying = false;
                }
            }
        }
    }

    /** "checking…", "newer version X available", "you have the current version" or "could not check". */
    private void setVersionLine(Source source, Optional<String> online, boolean checking) {
        Label label = (Label) sources.lookup("#version-" + source.id());
        if (label == null) {
            return;
        }
        String current = model.getManifest().product().version();
        label.textProperty().unbind();
        label.getStyleClass().remove("warning");
        if (checking) {
            label.textProperty().bind(model.i18n().text("source.version.checking"));
        } else if (online.isEmpty()) {
            label.textProperty().bind(model.i18n().text("source.version.unavailable"));
        } else if (Version.isNewer(online.get(), current)) {
            label.textProperty().bind(model.i18n().text("source.version.newer", online.get(), current));
            label.getStyleClass().add("warning");
        } else {
            label.textProperty().bind(model.i18n().text("source.version.current", current));
        }
        label.setVisible(true);
    }

    /** For tests: the outcome of the version check of a source, once known. */
    public Optional<Optional<String>> onlineVersion(String sourceId) {
        return Optional.ofNullable(onlineVersions.get(sourceId));
    }

    /** For tests: completes (on the JavaFX thread) when every check started by the last {@link #onEnter()} is applied. */
    public CompletableFuture<Void> versionChecksDone() {
        return checksApplied;
    }
}
