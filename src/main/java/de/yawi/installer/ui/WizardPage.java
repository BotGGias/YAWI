package de.yawi.installer.ui;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableStringValue;
import javafx.scene.Node;

/**
 * One page of the wizard as the {@link WizardFlow} sees it.
 *
 * <p>A page only supplies its content and answers a few questions; the frame
 * owns the buttons and the flow owns the order. Everything here has a default
 * so a plain page implements nothing but {@link #name()}, {@link #content()}
 * and {@link #title()}.
 */
public interface WizardPage {

    /** What pressing "Next" on this page means; the frame labels the button accordingly. */
    enum NextAction { NEXT, INSTALL, UNINSTALL, FINISH }

    /** The page name from the manifest's {@code <page name="...">}. */
    String name();

    /** The node shown in the frame's content area. */
    Node content();

    /** Heading in the frame, bound to the language. */
    ObservableStringValue title();

    /** Whether "Next" is enabled; pages with input bind this to their validation. */
    default ObservableBooleanValue nextAllowed() {
        return new SimpleBooleanProperty(true);
    }

    /** True if the page has nothing to show and the flow should step over it. */
    default boolean isSkippable() {
        return false;
    }

    default NextAction nextAction() {
        return NextAction.NEXT;
    }

    /**
     * True once this page is entered there is no way back: the installation
     * has started. The flow keeps "Back" disabled from then on.
     */
    default boolean locksBack() {
        return false;
    }

    /** Called every time the page becomes the current one, in either direction. */
    default void onEnter() {
    }

    /** Called before the flow moves to another page. */
    default void onLeave() {
    }
}
