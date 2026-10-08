package de.yawi.installer.cli;

import de.yawi.installer.core.answers.AnswerFile;
import de.yawi.installer.core.download.ProviderKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the silent run answers with, merged by rank: command
 * line over answer file; what neither gives stays empty and falls back to
 * the manifest's or the platform's default where the run needs it. The
 * source choice only comes from the file - there is no argument for it.
 *
 * @param lang            {@code --lang} or {@code <language>}
 * @param dest            {@code --dest} or {@code <destination>} (non-blank)
 * @param allUsers        {@code --all-users} or {@code <destination allUsers="true">}
 * @param components      {@code --components} or {@code <components>}; empty = the manifest's typical selection
 * @param inputs          file values, overridden by {@code --input.<id>}
 * @param sources         {@code <source kind>} per source id
 * @param licenseAccepted {@code --accept-license} or {@code <license accepted="true"/>}
 * @param fromFile        the file's answers, if there was one (for error messages that name the field)
 */
public record Answers(Optional<String> lang, Optional<String> dest, boolean allUsers, List<String> components,
                      Map<String, String> inputs, Map<String, ProviderKind> sources, boolean licenseAccepted,
                      Optional<AnswerFile> fromFile) {

    public Answers {
        Objects.requireNonNull(lang, "lang");
        Objects.requireNonNull(dest, "dest");
        components = List.copyOf(components);
        inputs = Map.copyOf(inputs);
        sources = Map.copyOf(sources);
        Objects.requireNonNull(fromFile, "fromFile");
    }

    public static Answers merge(Arguments args, Optional<AnswerFile> file) {
        AnswerFile answers = file.orElse(AnswerFile.empty());
        Optional<String> lang = Optional.ofNullable(args.lang()).or(answers::language);
        Optional<String> dest = Optional.ofNullable(args.dest())
                .or(() -> answers.destination().filter(d -> !d.isBlank()));
        boolean allUsers = args.allUsers() || answers.allUsers().orElse(false);
        List<String> components = !args.components().isEmpty() ? args.components()
                : answers.components().orElse(List.of());
        Map<String, String> inputs = new LinkedHashMap<>(answers.inputs());
        inputs.putAll(args.inputs());
        boolean license = args.acceptLicense() || answers.licenseAccepted();
        return new Answers(lang, dest, allUsers, components, inputs, answers.sources(), license, file);
    }

    /** True if the input's value came from the file and not from the command line. */
    public boolean inputFromFile(Arguments args, String id) {
        return !args.inputs().containsKey(id) && fromFile.map(f -> f.inputs().containsKey(id)).orElse(false);
    }
}
