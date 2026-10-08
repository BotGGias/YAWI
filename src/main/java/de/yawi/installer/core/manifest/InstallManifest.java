package de.yawi.installer.core.manifest;

import de.yawi.installer.core.platform.OperatingSystem;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The parsed, validated manifest. Immutable; everything else in the installer
 * reads from here and never from the XML again.
 *
 * @param messages translated manifest texts per language, keyed by
 *                 {@code message/@path}
 * @param xmlBytes the manifest as it was parsed, so the elevated process can
 *                 parse the very same text; empty if unknown
 */
public record InstallManifest(int schemaVersion, ManifestOrigin origin, Product product,
                              LanguageConfig languages, WizardConfig wizard, DestinationConfig destination,
                              List<Source> sources, List<Component> components, List<Preset> presets,
                              List<InstallStep> steps, IntegrationConfig integration,
                              Map<Locale, Map<String, String>> messages, byte[] xmlBytes) {

    public InstallManifest {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(languages, "languages");
        Objects.requireNonNull(wizard, "wizard");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(integration, "integration");
        sources = List.copyOf(sources);
        components = List.copyOf(components);
        presets = List.copyOf(presets);
        steps = List.copyOf(steps);
        messages = messages.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> Map.copyOf(e.getValue())));
        xmlBytes = xmlBytes == null ? new byte[0] : xmlBytes.clone();
    }

    // --- lookups ---------------------------------------------------------

    public Optional<Component> component(String id) {
        return find(components, Component::id, id);
    }

    public Optional<Source> source(String id) {
        return find(sources, Source::id, id);
    }

    public Optional<InstallStep> step(String id) {
        return find(steps, InstallStep::id, id);
    }

    public Optional<Preset> preset(String id) {
        return find(presets, Preset::id, id);
    }

    private static <T> Optional<T> find(List<T> items, Function<T, String> idOf, String id) {
        return items.stream().filter(item -> idOf.apply(item).equals(id)).findFirst();
    }

    // --- resolution ------------------------------------------------------

    /**
     * Completes a user's selection: adds every {@code required} component and
     * the transitive {@code dependsOn} closure. Unknown ids are ignored; the
     * validator has already rejected dangling references in the manifest, and
     * ids from outside ({@code --components=}) are checked by the caller.
     *
     * @return component ids in manifest order
     */
    public Set<String> resolveSelection(Collection<String> selectedIds) {
        Set<String> resolved = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        components.stream().filter(Component::required).map(Component::id).forEach(pending::add);
        pending.addAll(selectedIds);

        while (!pending.isEmpty()) {
            String id = pending.poll();
            if (!resolved.add(id)) {
                continue;
            }
            component(id).ifPresent(c -> pending.addAll(c.dependsOn()));
        }
        return components.stream()
                .map(Component::id)
                .filter(resolved::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The steps to run for a (already resolved) selection on {@code os}, in
     * execution order: components in manifest order, each component's steps
     * (filtered to those that apply to {@code os}) in the order it references
     * them, every step at most once at its first occurrence.
     */
    public List<InstallStep> stepsFor(Collection<String> componentIds, OperatingSystem os) {
        Set<String> wanted = Set.copyOf(componentIds);
        LinkedHashSet<String> stepIds = new LinkedHashSet<>();
        components.stream()
                .filter(c -> wanted.contains(c.id()))
                .forEach(c -> c.stepRefs().stream().filter(r -> r.appliesTo(os)).forEach(r -> stepIds.add(r.ref())));
        return stepIds.stream().map(id -> step(id).orElseThrow(
                () -> new IllegalStateException("step '" + id + "' referenced but not defined"))).toList();
    }

    /** The sources needed for a selection on {@code os}, in manifest order, each once. */
    public List<Source> sourcesFor(Collection<String> componentIds, OperatingSystem os) {
        Set<String> wanted = Set.copyOf(componentIds);
        Set<String> used = components.stream()
                .filter(c -> wanted.contains(c.id()))
                .flatMap(c -> c.uses().stream())
                .filter(r -> r.appliesTo(os))
                .map(OsRef::ref)
                .collect(Collectors.toSet());
        return sources.stream().filter(s -> used.contains(s.id())).toList();
    }
}
