package de.yawi.installer.core.manifest;

import java.util.List;
import java.util.Objects;

/**
 * A selectable {@code <component>}.
 *
 * @param sizeBytes   size shown in the selection, {@code -1} if not given
 * @param description may be null
 * @param dependsOn   ids of components that are forced along when this one is selected
 * @param uses        the sources this component needs, each optionally limited to one OS
 * @param stepRefs    the steps to run for this component, in order, each optionally limited to one OS
 */
public record Component(String id, boolean required, boolean selectedByDefault, long sizeBytes,
                        String name, String description, List<String> dependsOn, List<OsRef> uses,
                        List<OsRef> stepRefs, List<ComponentInput> inputs) {

    public Component {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        dependsOn = List.copyOf(dependsOn);
        uses = List.copyOf(uses);
        stepRefs = List.copyOf(stepRefs);
        inputs = List.copyOf(inputs);
    }
}
