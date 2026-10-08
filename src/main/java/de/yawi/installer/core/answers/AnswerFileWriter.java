package de.yawi.installer.core.answers;

import de.yawi.installer.core.xml.XmlText;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes an {@link AnswerFile} the way the wizard's "Save answers" does
 * : hand-serialised like the record, valid against
 * {@code installer-answers-1.xsd}, entries in the order they were given.
 */
public final class AnswerFileWriter {

    private AnswerFileWriter() {
    }

    /**
     * @param omitted input ids left out on purpose (secrets); named in a comment so the
     *                administrator knows to pass them on the command line
     */
    public static String format(AnswerFile answers, List<String> omitted) {
        StringBuilder out = new StringBuilder(512);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<answers version=\"").append(AnswerFile.FORMAT_VERSION).append('"');
        answers.product().ifPresent(p -> out.append(" product=\"").append(XmlText.escape(p)).append('"'));
        out.append(">\n");
        answers.language().ifPresent(l -> out.append("  <language>").append(XmlText.escape(l)).append("</language>\n"));
        answers.destination().ifPresent(d -> {
            out.append("  <destination");
            answers.allUsers().ifPresent(a -> out.append(" allUsers=\"").append(a).append('"'));
            out.append('>').append(XmlText.escape(d)).append("</destination>\n");
        });
        answers.components().filter(ids -> !ids.isEmpty()).ifPresent(ids -> {
            out.append("  <components>\n");
            ids.forEach(id -> out.append("    <component id=\"").append(XmlText.escape(id)).append("\"/>\n"));
            out.append("  </components>\n");
        });
        answers.associateFileTypes().ifPresent(a ->
                out.append("  <fileAssociations enabled=\"").append(a).append("\"/>\n"));
        answers.addPathEntries().ifPresent(a ->
                out.append("  <pathEntries enabled=\"").append(a).append("\"/>\n"));
        if (!answers.sources().isEmpty()) {
            out.append("  <sources>\n");
            answers.sources().forEach((id, kind) -> out.append("    <source id=\"").append(XmlText.escape(id))
                    .append("\" kind=\"").append(kind.name().toLowerCase(Locale.ROOT)).append("\"/>\n"));
            out.append("  </sources>\n");
        }
        if (!answers.inputs().isEmpty() || !omitted.isEmpty()) {
            out.append("  <inputs>\n");
            answers.inputs().forEach((id, value) -> out.append("    <input id=\"").append(XmlText.escape(id))
                    .append("\" value=\"").append(XmlText.escape(value)).append("\"/>\n"));
            omitted.forEach(id -> out.append("    <!-- input \"").append(XmlText.escape(id).replace("--", "- -"))
                    .append("\" looks like a secret and was not saved; pass it as an argument -->\n"));
            out.append("  </inputs>\n");
        }
        if (answers.licenseAccepted()) {
            out.append("  <license accepted=\"true\"/>\n");
        }
        out.append("</answers>\n");
        return out.toString();
    }

    /** Writes (or replaces) the file; the parent folder must exist. */
    public static Path write(AnswerFile answers, List<String> omitted, Path file) throws IOException {
        Files.writeString(file, format(answers, omitted), StandardCharsets.UTF_8);
        return file;
    }
}
