package de.yawi.installer.ui.page;

import de.yawi.installer.ui.InstallerModel;
import de.yawi.installer.ui.WizardPage;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableStringValue;
import javafx.scene.Node;
import javafx.scene.Parent;

import java.util.Objects;

/**
 * Base of every page controller: holds the shared model and implements the
 * {@link WizardPage} defaults.
 *
 * <p>The FXML root and the manifest page name are attached by
 * {@code PageRegistry} right after loading; the heading is
 * {@code page.<name>.title} from the bundle.
 */
public abstract class WizardPageController implements WizardPage {

    protected final InstallerModel model;
    private String name;
    private Parent content;
    private ObservableStringValue title;

    protected WizardPageController(InstallerModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    /** Called once by the registry; a page is never re-attached. */
    public final void attach(String pageName, Parent root) {
        if (this.name != null) {
            throw new IllegalStateException("page " + this.name + " already attached");
        }
        this.name = Objects.requireNonNull(pageName, "pageName");
        this.content = Objects.requireNonNull(root, "root");
        this.title = Bindings.createStringBinding(() -> model.i18n().get(titleKey(pageName)),
                model.localeProperty(), model.installModeProperty());
    }

    /**
     * The bundle key of the heading; {@code page.<name>.title} unless a page
     * reads differently while uninstalling. Re-evaluated on a
     * language or mode change.
     */
    protected String titleKey(String pageName) {
        return "page." + pageName + ".title";
    }

    /** True while the wizard removes an installation: the manifest pages step aside then. */
    protected final boolean uninstalling() {
        return model.isUninstalling();
    }

    /** True while the wizard repairs an installation: the component selection is locked. */
    protected final boolean repairing() {
        return model.getInstallMode() == InstallerModel.InstallMode.REPAIR;
    }

    @Override
    public final String name() {
        return requireAttached(name);
    }

    @Override
    public final Node content() {
        return requireAttached(content);
    }

    @Override
    public final ObservableStringValue title() {
        return requireAttached(title);
    }

    private <T> T requireAttached(T value) {
        if (value == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " is not attached to a page");
        }
        return value;
    }
}
