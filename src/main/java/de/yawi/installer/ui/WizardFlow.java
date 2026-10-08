package de.yawi.installer.ui;

import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableBooleanValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * The page order and the current position: a small state machine over the
 * pages the manifest lists.
 *
 * <p>Rules: skippable pages are stepped over in both directions; "Back" is
 * disabled on the first page and, once a page with {@link WizardPage#locksBack()}
 * has been entered, for good; "Next" follows the current page's
 * {@link WizardPage#nextAllowed()}; "Next" on the last page fires
 * {@link #setOnFinish}. Uses JavaFX properties only, no toolkit, so it is
 * unit tested headless.
 */
public final class WizardFlow {

    private static final Logger LOG = LoggerFactory.getLogger(WizardFlow.class);

    private final List<WizardPage> pages;
    private final ObjectProperty<WizardPage> currentPage = new SimpleObjectProperty<>(this, "currentPage");
    private final BooleanProperty backLocked = new SimpleBooleanProperty(this, "backLocked", false);
    private final BooleanBinding canGoBack;
    private final BooleanBinding canGoNext;
    private final ObjectProperty<WizardPage.NextAction> nextAction =
            new SimpleObjectProperty<>(this, "nextAction", WizardPage.NextAction.NEXT);
    private final ObjectProperty<ObservableBooleanValue> nextAllowed = new SimpleObjectProperty<>();

    private int index = -1;
    private Runnable onFinish = () -> { };

    public WizardFlow(List<WizardPage> pages) {
        this.pages = List.copyOf(pages);
        if (this.pages.isEmpty()) {
            throw new IllegalArgumentException("a wizard needs at least one page");
        }
        canGoBack = Bindings.createBooleanBinding(
                () -> !backLocked.get() && previousIndex(index) >= 0, currentPage, backLocked);
        // The current page's nextAllowed is a different observable per page;
        // the binding depends on the holder and is re-attached to the value
        // whenever the page changes.
        canGoNext = Bindings.createBooleanBinding(
                () -> nextAllowed.get() != null && nextAllowed.get().get(), nextAllowed);
        InvalidationListener invalidate = obs -> canGoNext.invalidate();
        nextAllowed.addListener((obs, old, current) -> {
            if (old != null) {
                old.removeListener(invalidate);
            }
            if (current != null) {
                current.addListener(invalidate);
            }
        });
    }

    public List<WizardPage> getPages() {
        return pages;
    }

    public ReadOnlyObjectProperty<WizardPage> currentPageProperty() {
        return currentPage;
    }

    public WizardPage getCurrentPage() {
        return currentPage.get();
    }

    public ObservableBooleanValue canGoBackProperty() {
        return canGoBack;
    }

    public ObservableBooleanValue canGoNextProperty() {
        return canGoNext;
    }

    /** True from the first page that {@link WizardPage#locksBack()} on; the cancel dialog reads it as "installing". */
    public ReadOnlyBooleanProperty backLockedProperty() {
        return backLocked;
    }

    public ReadOnlyObjectProperty<WizardPage.NextAction> nextActionProperty() {
        return nextAction;
    }

    /** Called when "Next" is pressed on the last page. */
    public void setOnFinish(Runnable onFinish) {
        this.onFinish = Objects.requireNonNull(onFinish, "onFinish");
    }

    /** Enters the first non-skippable page. */
    public void start() {
        if (index >= 0) {
            throw new IllegalStateException("already started");
        }
        int first = nextIndex(-1);
        if (first < 0) {
            throw new IllegalStateException("every page is skippable");
        }
        enter(first);
    }

    /** True if "Next" on the current page finishes the wizard. */
    public boolean isLast() {
        return nextIndex(index) < 0;
    }

    /**
     * Moves on if {@link #canGoNextProperty()} allows; finishes on the last
     * page. The page is left <em>before</em> the next one is looked for:
     * what {@code onLeave} puts into the model (the maintenance page's mode,
     * decides which pages step aside.
     */
    public void next() {
        if (!canGoNext.get()) {
            LOG.debug("Next ignored: page {} does not allow it", getCurrentPage().name());
            return;
        }
        getCurrentPage().onLeave();
        int target = nextIndex(index);
        if (target < 0) {
            LOG.info("Wizard finished on page {}", getCurrentPage().name());
            onFinish.run();
            return;
        }
        enter(target);
    }

    /** Moves back if {@link #canGoBackProperty()} allows. */
    public void back() {
        if (!canGoBack.get()) {
            LOG.debug("Back ignored on page {}", getCurrentPage().name());
            return;
        }
        getCurrentPage().onLeave();
        enter(previousIndex(index));
    }

    /**
     * Jumps back to an earlier page by name "change the
     * destination" from the summary, if going back is allowed at all and
     * the page lies behind the current one and is not skipped. Anything else
     * is ignored, like a disabled Back button.
     *
     * @return whether the page was entered
     */
    public boolean goBackTo(String pageName) {
        if (!canGoBack.get()) {
            LOG.debug("Jump to {} ignored on page {}: back is not allowed", pageName, getCurrentPage().name());
            return false;
        }
        for (int i = index - 1; i >= 0; i--) {
            WizardPage page = pages.get(i);
            if (page.name().equals(pageName)) {
                if (page.isSkippable()) {
                    return false;
                }
                getCurrentPage().onLeave();
                enter(i);
                return true;
            }
        }
        LOG.debug("Jump to {} ignored: not an earlier page", pageName);
        return false;
    }

    private void enter(int target) {
        WizardPage page = pages.get(target);
        index = target;
        if (page.locksBack()) {
            backLocked.set(true);
        }
        nextAction.set(page.nextAction());
        nextAllowed.set(page.nextAllowed());
        currentPage.set(page);
        LOG.debug("Entered page {} ({}/{})", page.name(), target + 1, pages.size());
        page.onEnter();
    }

    private int nextIndex(int from) {
        for (int i = from + 1; i < pages.size(); i++) {
            if (!pages.get(i).isSkippable()) {
                return i;
            }
        }
        return -1;
    }

    private int previousIndex(int from) {
        for (int i = from - 1; i >= 0; i--) {
            if (!pages.get(i).isSkippable()) {
                return i;
            }
        }
        return -1;
    }
}
