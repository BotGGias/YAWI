package de.yawi.installer.core.manifest;

import de.yawi.installer.core.platform.OperatingSystem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentSelectionTest {

    /** a -> b -> c, nothing required, c selected by default, typical = {c}, full = {a}. */
    private final ComponentSelection chain =
            new ComponentSelection(TestManifests.parse("/manifest/chain.xml"), OperatingSystem.LINUX);
    /** core required, server depends on core, typical = {core}, full = {core, server}. */
    private final ComponentSelection bundled = new ComponentSelection(TestManifests.bundled(), OperatingSystem.LINUX);

    @Test
    void initialSelectionIsTheTypicalPresetOrSelectedByDefault() {
        assertEquals(Set.of("core"), bundled.initialSelection());
        assertEquals(Set.of("c"), chain.initialSelection());

        // Without a typical preset the selectedByDefault flags decide.
        InstallManifest m = TestManifests.parse("/manifest/chain.xml");
        InstallManifest withoutPresets = new InstallManifest(m.schemaVersion(), m.origin(), m.product(),
                m.languages(), m.wizard(), m.destination(), m.sources(), m.components(), List.of(),
                m.steps(), m.integration(), m.messages(), m.xmlBytes());
        assertEquals(Set.of("c"), new ComponentSelection(withoutPresets, OperatingSystem.LINUX).initialSelection());
    }

    @Test
    void selectingPullsInTheWholeChainInManifestOrder() {
        assertEquals(List.of("c", "b", "a"), List.copyOf(chain.select("a", Set.of())));
        assertEquals(List.of("c", "b"), List.copyOf(chain.select("b", Set.of())));
    }

    @Test
    void dependenciesOfSelectedComponentsAreLocked() {
        Set<String> all = chain.select("a", Set.of());
        assertEquals(Set.of("a"), chain.dependents("b", all));
        assertEquals(List.of("b", "a"), List.copyOf(chain.dependents("c", all)));
        assertTrue(chain.isLocked("b", all));
        assertTrue(chain.isLocked("c", all));
        assertFalse(chain.isLocked("a", all));

        // Deselecting a locked component changes nothing.
        assertEquals(all, chain.deselect("c", all));

        // Once a is gone, b is free; c still locked by b.
        Set<String> withoutA = chain.deselect("a", all);
        assertEquals(Set.of("c", "b"), withoutA);
        assertFalse(chain.isLocked("b", withoutA));
        assertTrue(chain.isLocked("c", withoutA));
        assertEquals(Set.of(), chain.dependents("b", withoutA));
    }

    @Test
    void requiredComponentsAreAlwaysLockedWithoutNamingDependents() {
        assertTrue(bundled.isLocked("core", Set.of()));
        assertTrue(bundled.isRequired("core"));
        assertEquals(Set.of(), bundled.dependents("core", Set.of("core", "server")));
        assertEquals(Set.of("core"), bundled.deselect("core", Set.of("core")));
        assertFalse(bundled.isLocked("server", Set.of("core", "server")));
    }

    @Test
    void everythingCanBeDeselectedWhenNothingIsRequired() {
        assertEquals(Set.of(), chain.deselect("c", Set.of("c")));
        assertEquals(Set.of(), chain.resolve(Set.of()));
    }

    @Test
    void presetsMatchResolvedSelections() {
        assertEquals(Optional.of("typical"), bundled.matchingPreset(Set.of("core")));
        assertEquals(Optional.of("typical"), bundled.matchingPreset(Set.of()));
        assertEquals(Optional.of("full"), bundled.matchingPreset(Set.of("server")));
        assertEquals(Optional.of("full"), chain.matchingPreset(Set.of("a")));
        assertEquals(Optional.empty(), chain.matchingPreset(Set.of("b")));
        assertEquals(Set.of("core", "server"), bundled.presetSelection("full"));
        assertThrows(IllegalArgumentException.class, () -> bundled.presetSelection("nope"));
    }

    @Test
    void sizesFollowTheResolvedSelection() {
        assertEquals(ByteSize.parse("700MiB"), bundled.installBytes(Set.of()));
        assertEquals(ByteSize.parse("750MiB"), bundled.installBytes(Set.of("server")));
        assertEquals(ByteSize.parse("700MiB"), bundled.downloadBytes(Set.of("server")), "one shared source");

        assertEquals(ByteSize.parse("1MiB"), chain.installBytes(Set.of("c")));
        assertEquals(ByteSize.parse("7MiB"), chain.installBytes(Set.of("a")));
        assertEquals(ByteSize.parse("10MiB"), chain.downloadBytes(Set.of("c")));
        assertEquals(ByteSize.parse("40MiB"), chain.downloadBytes(Set.of("b")));
        assertEquals(0, chain.installBytes(Set.of()));
    }
}
