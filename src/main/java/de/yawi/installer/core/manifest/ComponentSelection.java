package de.yawi.installer.core.manifest;

import de.yawi.installer.core.platform.OperatingSystem;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rules of the component selection, independent of any UI: which
 * components start out selected, which cannot be deselected and why, what a
 * preset means and how much a selection weighs.
 *
 * <p>Stateless apart from the manifest; the caller (the components page, or
 * the CLI's {@code --components=}) owns the selected id set and passes
 * it in. Every returned set is resolved, i.e. it contains all required
 * components and the transitive {@code dependsOn} closure, in manifest order.
 *
 * <p>Deselecting is <em>prevented</em>, not cascaded: a component that is
 * required or that a selected component depends on is locked, and
 * {@link #dependents} names the reason. That keeps the tree free of
 * surprises — nothing disappears because something else was unticked.
 */
public final class ComponentSelection {

    private final InstallManifest manifest;
    private final OperatingSystem os;

    public ComponentSelection(InstallManifest manifest, OperatingSystem os) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.os = Objects.requireNonNull(os, "os");
    }

    /** Preset {@code typical} if the manifest has one, otherwise {@code selectedByDefault}; resolved. */
    public Set<String> initialSelection() {
        return manifest.preset(Preset.TYPICAL)
                .map(p -> presetSelection(p.id()))
                .orElseGet(() -> resolve(manifest.components().stream()
                        .filter(Component::selectedByDefault)
                        .map(Component::id)
                        .toList()));
    }

    /** Required components plus the transitive dependencies of {@code selected}, in manifest order. */
    public Set<String> resolve(Collection<String> selected) {
        return manifest.resolveSelection(selected);
    }

    /** The resolved selection of a preset. */
    public Set<String> presetSelection(String presetId) {
        Preset preset = manifest.preset(presetId)
                .orElseThrow(() -> new IllegalArgumentException("unknown preset '" + presetId + "'"));
        return resolve(preset.componentRefs());
    }

    /** The preset whose resolved selection equals {@code selected}; empty means "custom". */
    public Optional<String> matchingPreset(Collection<String> selected) {
        Set<String> resolved = resolve(selected);
        return manifest.presets().stream()
                .filter(p -> resolve(p.componentRefs()).equals(resolved))
                .map(Preset::id)
                .findFirst();
    }

    /**
     * Selected components that (transitively) depend on {@code id}: the
     * reason it cannot be deselected right now. Manifest order.
     */
    public Set<String> dependents(String id, Collection<String> selected) {
        if (isRequired(id)) {
            // Every resolution contains a required component; "required" is reason enough.
            return Set.of();
        }
        Set<String> selectedIds = Set.copyOf(selected);
        return manifest.components().stream()
                .filter(c -> selectedIds.contains(c.id()) && !c.id().equals(id))
                .filter(c -> resolve(Set.of(c.id())).contains(id))
                .map(Component::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** True if the component is required or a selected component needs it. */
    public boolean isLocked(String id, Collection<String> selected) {
        return isRequired(id) || !dependents(id, selected).isEmpty();
    }

    public boolean isRequired(String id) {
        return manifest.component(id).map(Component::required).orElse(false);
    }

    /** {@code selected} plus {@code id} and everything it depends on. */
    public Set<String> select(String id, Collection<String> selected) {
        Set<String> next = new LinkedHashSet<>(selected);
        next.add(id);
        return resolve(next);
    }

    /** {@code selected} without {@code id}; unchanged if the component is locked. */
    public Set<String> deselect(String id, Collection<String> selected) {
        if (isLocked(id, selected)) {
            return resolve(selected);
        }
        Set<String> next = new LinkedHashSet<>(selected);
        next.remove(id);
        return resolve(next);
    }

    /** Sum of the selected components' {@code size}, unknown sizes ignored. */
    public long installBytes(Collection<String> selected) {
        Set<String> resolved = resolve(selected);
        return manifest.components().stream()
                .filter(c -> resolved.contains(c.id()))
                .mapToLong(Component::sizeBytes)
                .filter(size -> size > 0)
                .sum();
    }

    /** Sum of the sizes of the sources the selection needs, unknown sizes ignored. */
    public long downloadBytes(Collection<String> selected) {
        return manifest.sourcesFor(resolve(selected), os).stream()
                .mapToLong(Source::sizeBytes)
                .filter(size -> size > 0)
                .sum();
    }
}
