package de.yawi.installer.core.state;

import de.yawi.installer.core.xml.SecureXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a record file back. A file whose installation crashed lacks the
 * closing tag; it is appended before parsing, so everything up to the last
 * flushed entry is available.
 */
public final class RecordReader {

    private static final String CLOSING = "</record>";

    private RecordReader() {
    }

    /**
     * @throws IOException if the file cannot be read or is no record file
     *         (wrong root, unsupported version, malformed XML)
     */
    public static InstallationRecord read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.stripTrailing().endsWith(CLOSING)) {
            text = repairTruncated(text) + CLOSING + "\n";
        }
        Document doc;
        try {
            doc = SecureXml.newDocumentBuilder().parse(new InputSource(new StringReader(text)));
        } catch (SAXException e) {
            throw new IOException("record " + file + " is not well-formed: " + e.getMessage(), e);
        }
        Element root = doc.getDocumentElement();
        if (!root.getTagName().equals("record")) {
            throw new IOException("record " + file + " has root <" + root.getTagName() + ">, expected <record>");
        }
        int version = Integer.parseInt(root.getAttribute("version"));
        if (version != InstallationRecord.FORMAT_VERSION) {
            throw new IOException("record " + file + " has format version " + version + ", supported: "
                    + InstallationRecord.FORMAT_VERSION);
        }

        List<String> components = new ArrayList<>();
        Map<String, String> inputs = new LinkedHashMap<>();
        List<InstallationRecord.Entry> entries = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element e)) {
                continue;
            }
            switch (e.getTagName()) {
                case "component" -> components.add(e.getAttribute("id"));
                case "input" -> inputs.put(e.getAttribute("id"), e.getAttribute("value"));
                case "file" -> entries.add(new InstallationRecord.CreatedFile(e.getAttribute("path")));
                case "dir" -> entries.add(new InstallationRecord.CreatedDirectory(e.getAttribute("path")));
                case "replaced" -> entries.add(new InstallationRecord.ReplacedFile(e.getAttribute("path"), e.getAttribute("backup")));
                case "mode" -> entries.add(new InstallationRecord.ModeChanged(e.getAttribute("path"), e.getAttribute("mode")));
                case "step" -> entries.add(e.getAttribute("event").equals("started")
                        ? new InstallationRecord.StepStarted(e.getAttribute("id"))
                        : new InstallationRecord.StepFinished(e.getAttribute("id"), e.getAttribute("outcome"),
                                e.hasAttribute("message") ? e.getAttribute("message") : null));
                case "finished" -> entries.add(new InstallationRecord.RunFinished(Instant.parse(e.getAttribute("at"))));
                default -> { } // unknown elements from a newer minor version are ignored
            }
        }
        InstallationRecord record = new InstallationRecord(
                root.getAttribute("product"), root.getAttribute("productVersion"),
                Instant.parse(root.getAttribute("started")), Path.of(root.getAttribute("destination")),
                components, inputs);
        entries.forEach(record::add);
        return record;
    }

    /** Drops a half-written last line (crash mid-flush) so the rest still parses. */
    private static String repairTruncated(String text) {
        int lastNewline = text.lastIndexOf('\n');
        String tail = lastNewline < 0 ? text : text.substring(lastNewline + 1);
        if (!tail.isBlank() && !tail.stripTrailing().endsWith("/>") && !tail.stripTrailing().endsWith(">")) {
            return text.substring(0, lastNewline + 1);
        }
        return text.endsWith("\n") ? text : text + "\n";
    }

}
