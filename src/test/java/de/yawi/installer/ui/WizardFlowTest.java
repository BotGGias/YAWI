package de.yawi.installer.ui;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableStringValue;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure property logic, no toolkit: fake pages instead of FXML. */
class WizardFlowTest {

    /** Records enter/leave calls into a shared log. */
    private static final class FakePage implements WizardPage {
        final String name;
        final List<String> log;
        final SimpleBooleanProperty allowed = new SimpleBooleanProperty(true);
        boolean skippable;
        boolean locksBack;
        NextAction action = NextAction.NEXT;

        FakePage(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override public String name() { return name; }
        @Override public Node content() { return null; } // never shown here
        @Override public ObservableStringValue title() { return new SimpleStringProperty(name); }
        @Override public ObservableBooleanValue nextAllowed() { return allowed; }
        @Override public boolean isSkippable() { return skippable; }
        @Override public NextAction nextAction() { return action; }
        @Override public boolean locksBack() { return locksBack; }
        @Override public void onEnter() { log.add("enter " + name); }
        @Override public void onLeave() { log.add("leave " + name); }
    }

    private final List<String> log = new ArrayList<>();

    private FakePage page(String name) {
        return new FakePage(name, log);
    }

    @Test
    void startsOnTheFirstPageWithBackDisabled() {
        FakePage a = page("a");
        FakePage b = page("b");
        WizardFlow flow = new WizardFlow(List.of(a, b));
        flow.start();

        assertEquals(a, flow.getCurrentPage());
        assertFalse(flow.canGoBackProperty().get());
        assertTrue(flow.canGoNextProperty().get());
        assertFalse(flow.isLast());
        assertEquals(List.of("enter a"), log);
    }

    @Test
    void nextAndBackCallLeaveThenEnterAndKeepOrder() {
        FakePage a = page("a");
        FakePage b = page("b");
        WizardFlow flow = new WizardFlow(List.of(a, b));
        flow.start();

        flow.next();
        assertEquals(b, flow.getCurrentPage());
        assertTrue(flow.canGoBackProperty().get());
        assertTrue(flow.isLast());

        flow.back();
        assertEquals(a, flow.getCurrentPage());
        assertEquals(List.of("enter a", "leave a", "enter b", "leave b", "enter a"), log);
    }

    @Test
    void backIsIgnoredOnTheFirstPage() {
        FakePage a = page("a");
        WizardFlow flow = new WizardFlow(List.of(a, page("b")));
        flow.start();

        flow.back();

        assertEquals(a, flow.getCurrentPage());
        assertEquals(List.of("enter a"), log);
    }

    @Test
    void nextFollowsThePagesNextAllowed() {
        FakePage a = page("a");
        FakePage b = page("b");
        WizardFlow flow = new WizardFlow(List.of(a, b));
        flow.start();

        a.allowed.set(false);
        assertFalse(flow.canGoNextProperty().get());
        flow.next();
        assertEquals(a, flow.getCurrentPage(), "must not move while next is disallowed");

        a.allowed.set(true);
        assertTrue(flow.canGoNextProperty().get());
        flow.next();
        assertEquals(b, flow.getCurrentPage());

        // The binding follows the new page's own observable.
        b.allowed.set(false);
        assertFalse(flow.canGoNextProperty().get());
        a.allowed.set(false); // the old page no longer matters
        b.allowed.set(true);
        assertTrue(flow.canGoNextProperty().get());
    }

    @Test
    void skippablePagesAreSteppedOverInBothDirections() {
        FakePage a = page("a");
        FakePage license = page("license");
        license.skippable = true;
        FakePage c = page("c");
        WizardFlow flow = new WizardFlow(List.of(a, license, c));
        flow.start();

        flow.next();
        assertEquals(c, flow.getCurrentPage());
        flow.back();
        assertEquals(a, flow.getCurrentPage());
        assertFalse(log.contains("enter license"));
    }

    @Test
    void skippableFirstAndLastPagesAreHandled() {
        FakePage first = page("first");
        first.skippable = true;
        FakePage a = page("a");
        FakePage last = page("last");
        last.skippable = true;
        WizardFlow flow = new WizardFlow(List.of(first, a, last));
        flow.start();

        assertEquals(a, flow.getCurrentPage());
        assertFalse(flow.canGoBackProperty().get());
        assertTrue(flow.isLast());
    }

    @Test
    void backStaysLockedOnceAnInstallPageWasEntered() {
        FakePage a = page("a");
        FakePage progress = page("progress");
        progress.locksBack = true;
        FakePage finish = page("finish");
        WizardFlow flow = new WizardFlow(List.of(a, progress, finish));
        flow.start();
        assertFalse(flow.backLockedProperty().get());

        flow.next();
        assertTrue(flow.backLockedProperty().get());
        assertFalse(flow.canGoBackProperty().get());
        flow.back();
        assertEquals(progress, flow.getCurrentPage());

        flow.next();
        assertEquals(finish, flow.getCurrentPage());
        assertFalse(flow.canGoBackProperty().get());
    }

    @Test
    void nextActionFollowsTheCurrentPage() {
        FakePage summary = page("summary");
        summary.action = WizardPage.NextAction.INSTALL;
        FakePage finish = page("finish");
        finish.action = WizardPage.NextAction.FINISH;
        WizardFlow flow = new WizardFlow(List.of(page("a"), summary, finish));
        flow.start();

        assertEquals(WizardPage.NextAction.NEXT, flow.nextActionProperty().get());
        flow.next();
        assertEquals(WizardPage.NextAction.INSTALL, flow.nextActionProperty().get());
        flow.next();
        assertEquals(WizardPage.NextAction.FINISH, flow.nextActionProperty().get());
    }

    @Test
    void nextOnTheLastPageFinishes() {
        FakePage a = page("a");
        WizardFlow flow = new WizardFlow(List.of(a));
        boolean[] finished = {false};
        flow.setOnFinish(() -> finished[0] = true);
        flow.start();

        flow.next();

        assertTrue(finished[0]);
        assertEquals(a, flow.getCurrentPage());
        assertEquals(List.of("enter a", "leave a"), log);
    }

    @Test
    void rejectsEmptyOrAllSkippableFlowsAndDoubleStart() {
        assertThrows(IllegalArgumentException.class, () -> new WizardFlow(List.of()));

        FakePage only = page("only");
        only.skippable = true;
        assertThrows(IllegalStateException.class, () -> new WizardFlow(List.of(only)).start());

        WizardFlow flow = new WizardFlow(List.of(page("a")));
        flow.start();
        assertThrows(IllegalStateException.class, flow::start);
    }

    @Test
    void goBackToJumpsOnlyToEarlierNonSkippedPagesAndOnlyWhenBackIsAllowed() {
        FakePage a = page("a");
        FakePage b = page("b");
        FakePage c = page("c");
        FakePage d = page("d");
        WizardFlow flow = new WizardFlow(List.of(a, b, c, d));
        flow.start();
        flow.next();
        flow.next();
        assertEquals(c, flow.getCurrentPage());
        log.clear();

        assertFalse(flow.goBackTo("d"), "not an earlier page");
        assertFalse(flow.goBackTo("nope"));
        assertEquals(c, flow.getCurrentPage());
        assertTrue(flow.goBackTo("a"), "skips b on the way");
        assertEquals(a, flow.getCurrentPage());
        assertEquals(List.of("leave c", "enter a"), log);

        flow.next();
        flow.next();
        b.skippable = true;
        assertFalse(flow.goBackTo("b"), "a skipped page is not entered");
        d.locksBack = true;
        flow.next();
        assertEquals(d, flow.getCurrentPage());
        assertFalse(flow.goBackTo("a"), "no jump once back is locked");
        assertEquals(d, flow.getCurrentPage());
    }
}
