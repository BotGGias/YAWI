package de.yawi.installer.core.manifest;

import de.yawi.installer.core.xml.SecureXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a manifest: size limit, well-formedness, schema, structure, business
 * rules - in that order, each on top of the previous one.
 *
 * <p>Schema problems carry line and column, the later ones the affected id.
 * All of them are {@link ManifestProblem}s and a manifest with at least one
 * error is rejected as a whole with every problem attached.
 */
public final class ManifestParser {

    /** A manifest larger than this is rejected before it is parsed. */
    public static final long MAX_MANIFEST_BYTES = 1L << 20;

    /** Loaded from the classpath only; never from the manifest's schemaLocation hint. */
    static final String SCHEMA_RESOURCE = "/installer-manifest-1.xsd";

    private static final Logger LOG = LoggerFactory.getLogger(ManifestParser.class);

    private static final class SchemaHolder {
        static final Schema SCHEMA = loadSchema();

        private static Schema loadSchema() {
            URL url = ManifestParser.class.getResource(SCHEMA_RESOURCE);
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

    /**
     * @throws ManifestException if the stream cannot be read, is too large or
     *         the manifest is rejected
     */
    public InstallManifest parse(InputStream in, ManifestOrigin origin) {
        byte[] bytes;
        try {
            bytes = SecureXml.readLimited(in, MAX_MANIFEST_BYTES);
        } catch (SecureXml.SizeLimitExceededException e) {
            throw new ManifestException("Manifest " + origin.location() + " is larger than the allowed "
                    + MAX_MANIFEST_BYTES + " bytes", List.of(), e);
        } catch (IOException e) {
            throw new ManifestException("Manifest " + origin.location() + " could not be read: "
                    + e.getMessage(), List.of(), e);
        }
        return parse(bytes, origin);
    }

    public InstallManifest parse(byte[] bytes, ManifestOrigin origin) {
        String where = origin.location();

        // 1. well-formedness - DOCTYPE and entities are refused here
        Document doc;
        try {
            doc = SecureXml.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (SAXParseException e) {
            throw new ManifestException("Manifest " + where + " is not well-formed",
                    List.of(problem(ManifestProblem.Severity.ERROR, e)), e);
        } catch (SAXException | IOException e) {
            throw new ManifestException("Manifest " + where + " could not be parsed: " + e.getMessage(),
                    List.of(), e);
        }

        // 2. schema - validated from the raw bytes so that positions are known
        List<ManifestProblem> problems = new ArrayList<>(validateSchema(bytes));
        if (problems.stream().anyMatch(ManifestProblem::isError)) {
            throw new ManifestException("Manifest " + where + " violates the schema", problems);
        }

        // 3. structure that the schema cannot express
        ManifestReader reader = new ManifestReader();
        InstallManifest manifest = reader.read(doc, origin, bytes);
        problems.addAll(reader.problems());
        if (problems.stream().anyMatch(ManifestProblem::isError)) {
            throw new ManifestException("Manifest " + where + " is incomplete", problems);
        }

        // 4. business rules
        problems.addAll(new ManifestValidator().validate(manifest));
        if (problems.stream().anyMatch(ManifestProblem::isError)) {
            throw new ManifestException("Manifest " + where + " is invalid", problems);
        }

        problems.forEach(p -> LOG.warn("Manifest {}: {}", where, p));
        return manifest;
    }

    private static List<ManifestProblem> validateSchema(byte[] bytes) {
        List<ManifestProblem> problems = new ArrayList<>();
        Validator validator = SecureXml.newValidator(SchemaHolder.SCHEMA);
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
                problems.add(problem(ManifestProblem.Severity.WARNING, e));
            }

            @Override
            public void error(SAXParseException e) {
                // collect and carry on: the packager gets every violation at once
                problems.add(problem(ManifestProblem.Severity.ERROR, e));
            }

            @Override
            public void fatalError(SAXParseException e) throws SAXException {
                throw e;
            }
        });
        try {
            validator.validate(new StreamSource(new ByteArrayInputStream(bytes)));
        } catch (SAXParseException e) {
            problems.add(problem(ManifestProblem.Severity.ERROR, e));
        } catch (SAXException | IOException e) {
            problems.add(ManifestProblem.error(0, 0, e.getMessage()));
        }
        return problems;
    }

    private static ManifestProblem problem(ManifestProblem.Severity severity, SAXParseException e) {
        return new ManifestProblem(severity, Math.max(e.getLineNumber(), 0), Math.max(e.getColumnNumber(), 0),
                null, e.getMessage());
    }
}
