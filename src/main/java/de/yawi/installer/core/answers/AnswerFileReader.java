package de.yawi.installer.core.answers;

import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.error.AnswerFileException;
import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.ComponentInput;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.Provider;
import de.yawi.installer.core.manifest.Source;
import de.yawi.installer.core.xml.SecureXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads an answer file: size limit, well-formedness, schema,
 * version - in that order, like the manifest. Every violation is an
 * {@link AnswerFileException} (exit code 2) that names the file and the
 * offending element, with line and column where the schema found it.
 *
 * <p>{@link #check} is the second half, once the manifest is known: ids
 * must exist, a chosen source kind must be offered. A value the file gives
 * is never quietly dropped or replaced.
 */
public final class AnswerFileReader {

    /** Loaded from the classpath only; never from a schemaLocation hint in the file. */
    static final String SCHEMA_RESOURCE = "/installer-answers-1.xsd";

    private static final Logger LOG = LoggerFactory.getLogger(AnswerFileReader.class);

    private static final class SchemaHolder {
        static final Schema SCHEMA = loadSchema();

        private static Schema loadSchema() {
            URL url = AnswerFileReader.class.getResource(SCHEMA_RESOURCE);
            if (url == null) {
                throw new IllegalStateException("schema " + SCHEMA_RESOURCE + " missing from classpath");
            }
            try {
                return SecureXml.newSchemaFactory().newSchema(url);
            } catch (SAXException e) {
                throw new IllegalStateException("bundled schema " + SCHEMA_RESOURCE + " is broken", e);
            }
        }
    }

    private AnswerFileReader() {
    }

    /** @throws AnswerFileException if the file cannot be read or is rejected */
    public static AnswerFile read(Path file) {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = SecureXml.readLimited(in, AnswerFile.MAX_BYTES);
        } catch (SecureXml.SizeLimitExceededException e) {
            throw new AnswerFileException(file, List.of("larger than the allowed " + AnswerFile.MAX_BYTES + " bytes"), e);
        } catch (IOException e) {
            throw new AnswerFileException(file, List.of("cannot be read: " + e.getMessage()), e);
        }
        return parse(bytes, file);
    }

    /** @param file only named in problems; nothing is read from it */
    public static AnswerFile parse(byte[] bytes, Path file) {
        // 1. well-formedness - DOCTYPE and entities are refused here
        Document doc;
        try {
            doc = SecureXml.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (SAXParseException e) {
            throw new AnswerFileException(file, List.of("not well-formed: " + position(e) + e.getMessage()), e);
        } catch (SAXException | IOException e) {
            throw new AnswerFileException(file, List.of("cannot be parsed: " + e.getMessage()), e);
        }

        // 2. schema - from the raw bytes so that positions are known; every violation at once
        List<String> problems = validateSchema(bytes);
        if (!problems.isEmpty()) {
            throw new AnswerFileException(file, problems, null);
        }

        // 3. version
        Element root = doc.getDocumentElement();
        int version = Integer.parseInt(root.getAttribute("version"));
        if (version != AnswerFile.FORMAT_VERSION) {
            throw new AnswerFileException(file, "format version " + version + " is not supported (this installer reads "
                    + AnswerFile.FORMAT_VERSION + ")");
        }

        Optional<String> product = attribute(root, "product");
        Optional<String> language = Optional.empty();
        Optional<String> destination = Optional.empty();
        Optional<Boolean> allUsers = Optional.empty();
        Optional<List<String>> components = Optional.empty();
        Map<String, ProviderKind> sources = new LinkedHashMap<>();
        Map<String, String> inputs = new LinkedHashMap<>();
        boolean licenseAccepted = false;
        Optional<Boolean> associateFileTypes = Optional.empty();
        Optional<Boolean> addPathEntries = Optional.empty();
        for (Element e : children(root)) {
            switch (e.getTagName()) {
                case "language" -> language = Optional.of(e.getTextContent().strip());
                case "destination" -> {
                    destination = Optional.of(e.getTextContent().strip());
                    allUsers = attribute(e, "allUsers").map(Boolean::parseBoolean);
                }
                case "components" -> {
                    List<String> ids = new ArrayList<>();
                    children(e).forEach(c -> ids.add(c.getAttribute("id")));
                    components = Optional.of(ids);
                }
                case "sources" -> children(e).forEach(s -> sources.put(s.getAttribute("id"),
                        ProviderKind.valueOf(s.getAttribute("kind").toUpperCase(Locale.ROOT))));
                case "inputs" -> children(e).forEach(i -> inputs.put(i.getAttribute("id"), i.getAttribute("value")));
                case "fileAssociations" -> associateFileTypes = attribute(e, "enabled").map(Boolean::parseBoolean);
                case "pathEntries" -> addPathEntries = attribute(e, "enabled").map(Boolean::parseBoolean);
                case "license" -> licenseAccepted = Boolean.parseBoolean(e.getAttribute("accepted"));
                default -> { } // the schema allows nothing else
            }
        }
        AnswerFile answers = new AnswerFile(product, language, destination, allUsers, components, sources, inputs,
                licenseAccepted, associateFileTypes, addPathEntries);
        // Input values stay out of the log: they may be passwords.
        LOG.info("Answer file {}: language {}, destination {} (allUsers {}), components {}, sources {}, inputs {}, license {}",
                file, language, destination, allUsers, components, sources, inputs.keySet(), licenseAccepted);
        return answers;
    }

    /**
     * The answers against the manifest they are meant for: product id,
     * component, source and input ids, and whether a source offers the
     * chosen kind. Every mismatch is reported, in one exception.
     *
     * @throws AnswerFileException if anything in the file does not fit the manifest
     */
    public static void check(AnswerFile answers, InstallManifest manifest, Path file) {
        List<String> problems = new ArrayList<>();
        answers.product().filter(id -> !id.equals(manifest.product().id())).ifPresent(id ->
                problems.add("<answers product=\"" + id + "\">: this installer is for product '"
                        + manifest.product().id() + "'"));
        answers.language().ifPresent(code -> {
            Locale wanted = Locale.forLanguageTag(code);
            if (!manifest.languages().resolve(wanted).getLanguage().equals(wanted.getLanguage())) {
                LOG.warn("Answer file {}: language '{}' is not offered by the manifest; using {}", file, code,
                        manifest.languages().resolve(wanted));
            }
        });
        answers.components().ifPresent(ids -> ids.stream()
                .filter(id -> manifest.component(id).isEmpty())
                .forEach(id -> problems.add("<component id=\"" + id + "\">: unknown component")));
        answers.sources().forEach((id, kind) -> {
            Optional<Source> source = manifest.source(id);
            if (source.isEmpty()) {
                problems.add("<source id=\"" + id + "\">: unknown source");
            } else if (!source.get().hasProviderOfType(providerType(kind))) {
                problems.add("<source id=\"" + id + "\" kind=\"" + kind.name().toLowerCase(Locale.ROOT)
                        + "\">: the source offers no " + kind.name().toLowerCase(Locale.ROOT) + " provider");
            }
        });
        answers.inputs().keySet().stream()
                .filter(id -> manifest.components().stream().map(Component::inputs).flatMap(List::stream)
                        .map(ComponentInput::id).noneMatch(id::equals))
                .forEach(id -> problems.add("<input id=\"" + id + "\">: no component has this input"));
        if (!problems.isEmpty()) {
            throw new AnswerFileException(file, problems, null);
        }
    }

    private static Class<? extends Provider> providerType(ProviderKind kind) {
        return switch (kind) {
            case BUNDLED -> Provider.Bundled.class;
            case HTTP -> Provider.Http.class;
            case TORRENT -> Provider.Torrent.class;
        };
    }

    private static List<String> validateSchema(byte[] bytes) {
        List<String> problems = new ArrayList<>();
        Validator validator = SecureXml.newValidator(SchemaHolder.SCHEMA);
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
            }

            @Override
            public void error(SAXParseException e) {
                problems.add(position(e) + e.getMessage());
            }

            @Override
            public void fatalError(SAXParseException e) throws SAXException {
                throw e;
            }
        });
        try {
            validator.validate(new StreamSource(new ByteArrayInputStream(bytes)));
        } catch (SAXParseException e) {
            problems.add(position(e) + e.getMessage());
        } catch (SAXException | IOException e) {
            problems.add(e.getMessage());
        }
        return problems;
    }

    private static String position(SAXParseException e) {
        return e.getLineNumber() > 0 ? "line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ": " : "";
    }

    private static Optional<String> attribute(Element e, String name) {
        return e.hasAttribute(name) ? Optional.of(e.getAttribute(name)) : Optional.empty();
    }

    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e) {
                elements.add(e);
            }
        }
        return elements;
    }
}
