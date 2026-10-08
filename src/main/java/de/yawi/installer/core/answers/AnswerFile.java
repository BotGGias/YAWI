package de.yawi.installer.core.answers;

import de.yawi.installer.core.download.ProviderKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The answers of a silent installation as a file gives them:
 * everything the wizard would ask for, each part optional. What is absent
 * falls back to the command line's or the manifest's default; what is
 * present has been checked against the schema, so a given
 * {@code <input>} always has a value.
 *
 * @param product         the manifest's product id this file was written for, if it says
 * @param language        language code as in the manifest's {@code <languages>}
 * @param destination     the target folder; empty text means "the default for the scope"
 * @param allUsers        the {@code allUsers} attribute of {@code <destination>}
 * @param components      explicit selection, in file order; absent means the manifest's typical one
 * @param sources         preferred kind per source id
 * @param inputs          values per input id, in file order
 * @param licenseAccepted {@code <license accepted="true"/>}
 * @param associateFileTypes {@code <fileAssociations enabled="..."/>}: whether the file associations are created
 * @param addPathEntries  {@code <pathEntries enabled="..."/>}: whether the PATH entries are added
 */
public record AnswerFile(Optional<String> product, Optional<String> language, Optional<String> destination,
                         Optional<Boolean> allUsers, Optional<List<String>> components,
                         Map<String, ProviderKind> sources, Map<String, String> inputs, boolean licenseAccepted,
                         Optional<Boolean> associateFileTypes, Optional<Boolean> addPathEntries) {

    /** The {@code version} attribute this installer reads and writes. */
    public static final int FORMAT_VERSION = 1;

    /** A file larger than this is rejected before it is parsed. */
    public static final long MAX_BYTES = 1L << 20;

    public AnswerFile {
        product = product == null ? Optional.empty() : product;
        language = language == null ? Optional.empty() : language;
        destination = destination == null ? Optional.empty() : destination;
        allUsers = allUsers == null ? Optional.empty() : allUsers;
        components = components == null ? Optional.empty() : components.map(List::copyOf);
        // Insertion order kept: the writer puts the entries back in the order they came.
        sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        associateFileTypes = associateFileTypes == null ? Optional.empty() : associateFileTypes;
        addPathEntries = addPathEntries == null ? Optional.empty() : addPathEntries;
    }

    /** A file that answers nothing; every value comes from elsewhere. */
    public static AnswerFile empty() {
        return new AnswerFile(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Map.of(), Map.of(), false, Optional.empty(), Optional.empty());
    }
}
