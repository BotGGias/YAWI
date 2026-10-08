package de.yawi.installer.core.manifest;

import java.util.List;
import java.util.Objects;

/** A named component selection ({@code <preset>}), e.g. {@code typical} or {@code full}. */
public record Preset(String id, List<String> componentRefs) {

    /** The preset the wizard proposes; must not be empty unless a component is required. */
    public static final String TYPICAL = "typical";

    public Preset {
        Objects.requireNonNull(id, "id");
        componentRefs = List.copyOf(componentRefs);
    }
}
