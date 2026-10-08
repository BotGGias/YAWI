package de.yawi.installer.core.manifest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The {@code <integration>} block: shortcuts, PATH entries, file associations. */
public record IntegrationConfig(List<Shortcut> shortcuts, List<String> pathEntries,
                                List<FileAssociation> fileAssociations) {

    /** No integration at all. */
    public static final IntegrationConfig NONE = new IntegrationConfig(List.of(), List.of(), List.of());

    public IntegrationConfig {
        shortcuts = List.copyOf(shortcuts);
        pathEntries = List.copyOf(pathEntries);
        fileAssociations = List.copyOf(fileAssociations);
    }

    public Optional<Shortcut> shortcut(String id) {
        return shortcuts.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** The extensions of the associations that are on by default (E13-S02: {@code default="true"}). */
    public List<String> defaultAssociationExtensions() {
        return fileAssociations.stream().filter(FileAssociation::byDefault).map(FileAssociation::extension).toList();
    }

    /** @param icon may be null */
    public record Shortcut(String id, boolean desktop, boolean menu, String name, String target, String icon) {
        public Shortcut {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(target, "target");
        }

        public Optional<String> iconOrEmpty() {
            return Optional.ofNullable(icon);
        }
    }

    /**
     * @param description may be null
     * @param byDefault   {@code @default}: whether the association is created unless the user turns it off (E13-S02)
     */
    public record FileAssociation(String extension, String target, String description, boolean byDefault) {
        public FileAssociation {
            Objects.requireNonNull(extension, "extension");
            Objects.requireNonNull(target, "target");
        }

        public Optional<String> descriptionOrEmpty() {
            return Optional.ofNullable(description);
        }
    }
}
