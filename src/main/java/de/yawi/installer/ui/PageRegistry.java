package de.yawi.installer.ui;

import de.yawi.installer.core.manifest.WizardConfig;
import de.yawi.installer.ui.i18n.I18nFxml;
import de.yawi.installer.ui.page.ComponentsPageController;
import de.yawi.installer.ui.page.DestinationPageController;
import de.yawi.installer.ui.page.FinishPageController;
import de.yawi.installer.ui.page.MaintenancePageController;
import de.yawi.installer.ui.page.LicensePageController;
import de.yawi.installer.ui.page.SourcePageController;
import de.yawi.installer.ui.page.SummaryPageController;
import de.yawi.installer.ui.page.UninstallPageController;
import de.yawi.installer.ui.page.ProgressPageController;
import de.yawi.installer.ui.page.WelcomePageController;
import de.yawi.installer.ui.page.WizardPageController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Binds the page names of the manifest ({@link WizardConfig#KNOWN_PAGES}) to
 * FXML files and controllers, and builds the page list for a manifest.
 *
 * <p>Every page is loaded once and kept; going back therefore shows the page
 * exactly as the user left it.
 */
public final class PageRegistry {

    /** The FXML files sit next to the page controllers. */
    static final String FXML_BASE = "/de/yawi/installer/ui/page/";

    private static final Logger LOG = LoggerFactory.getLogger(PageRegistry.class);

    public record Registration(String fxml, Class<? extends WizardPageController> controller) {
    }

    private static final Map<String, Registration> REGISTRATIONS = Map.of(
            "welcome", new Registration("welcome.fxml", WelcomePageController.class),
            "license", new Registration("license.fxml", LicensePageController.class),
            "components", new Registration("components.fxml", ComponentsPageController.class),
            "destination", new Registration("destination.fxml", DestinationPageController.class),
            "source", new Registration("source.fxml", SourcePageController.class),
            "summary", new Registration("summary.fxml", SummaryPageController.class),
            "progress", new Registration("progress.fxml", ProgressPageController.class),
            "finish", new Registration("finish.fxml", FinishPageController.class),
            WizardConfig.MAINTENANCE_PAGE, new Registration("maintenance.fxml", MaintenancePageController.class),
            WizardConfig.UNINSTALL_PAGE, new Registration("uninstall.fxml", UninstallPageController.class));

    private PageRegistry() {
    }

    /** Must equal {@link WizardConfig#KNOWN_PAGES} plus {@link WizardConfig#IMPLICIT_PAGES}; a test enforces it. */
    public static Set<String> registeredNames() {
        return REGISTRATIONS.keySet();
    }

    public static Registration registration(String name) {
        Registration registration = REGISTRATIONS.get(name);
        if (registration == null) {
            // The validator rejects unknown names, so this is a programming error.
            throw new IllegalArgumentException("no page registered for '" + name + "'");
        }
        return registration;
    }

    /**
     * Loads the pages the manifest lists, in manifest order, with the
     * maintenance page and the uninstall page in front;
     * they skip themselves when the product is not registered or the user
     * did not choose to uninstall.
     */
    public static List<WizardPage> build(InstallerModel model) {
        List<WizardPage> pages = new ArrayList<>();
        pages.add(load(WizardConfig.MAINTENANCE_PAGE, model));
        pages.add(load(WizardConfig.UNINSTALL_PAGE, model));
        for (String name : model.getManifest().wizard().pages()) {
            pages.add(load(name, model));
        }
        return pages;
    }

    /**
     * @throws UncheckedIOException if the FXML cannot be loaded; the caller
     *         turns this into a message for the user rather than a stack trace
     */
    public static WizardPage load(String name, InstallerModel model) {
        Registration registration = registration(name);
        URL url = PageRegistry.class.getResource(FXML_BASE + registration.fxml());
        if (url == null) {
            throw new IllegalStateException("FXML missing for page '" + name + "': " + registration.fxml());
        }
        LOG.debug("Loading page {} from {}", name, registration.fxml());
        FXMLLoader loader = I18nFxml.loader(url, model.i18n(), type -> createController(type, model));
        Parent root;
        try {
            root = I18nFxml.load(loader, model.i18n());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load page '" + name + "'", e);
        }
        Object controller = loader.getController();
        if (!registration.controller().isInstance(controller)) {
            throw new IllegalStateException("page '" + name + "' declares " + controller.getClass().getName()
                    + " but " + registration.controller().getName() + " is registered");
        }
        WizardPageController page = registration.controller().cast(controller);
        page.attach(name, root);
        return page;
    }

    /** Gives every page controller the shared model. */
    private static Object createController(Class<?> type, InstallerModel model) {
        try {
            return type.getConstructor(InstallerModel.class).newInstance(model);
        } catch (NoSuchMethodException e) {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException nested) {
                throw new IllegalStateException("Cannot instantiate controller " + type, nested);
            }
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot instantiate controller " + type, e);
        }
    }
}
