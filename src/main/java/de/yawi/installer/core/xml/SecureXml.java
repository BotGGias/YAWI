package de.yawi.installer.core.xml;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;

/**
 * The only place in the project that creates XML parsers and validators.
 *
 * <p>Every reader is hardened the same way: DTDs are rejected outright, external
 * entities and schemas are never fetched, and secure processing limits apply.
 */
public final class SecureXml {

    private static final String DISALLOW_DOCTYPE =
            "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private static final ErrorHandler THROWING_HANDLER = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {
        }

        @Override
        public void error(SAXParseException e) throws SAXParseException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXParseException {
            throw e;
        }
    };

    private SecureXml() {
    }

    /** Namespace-aware DOM builder that rejects any DOCTYPE. */
    public static DocumentBuilder newDocumentBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(DISALLOW_DOCTYPE, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Without a handler Xerces prints "[Fatal Error]" to stderr before
            // throwing; callers report problems themselves.
            builder.setErrorHandler(THROWING_HANDLER);
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("JDK XML parser does not support secure processing", e);
        }
    }

    /** W3C schema factory that never resolves external schemas or DTDs. */
    public static SchemaFactory newSchemaFactory() {
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory;
        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            throw new IllegalStateException("JDK schema factory does not support secure processing", e);
        }
    }

    /** Validator for the given schema with external access switched off. */
    public static Validator newValidator(Schema schema) {
        try {
            Validator validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return validator;
        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            throw new IllegalStateException("JDK validator does not support secure processing", e);
        }
    }

    /** StAX factory without DTD support; for streaming readers. */
    public static XMLInputFactory newXMLInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    /**
     * Reads the stream completely but refuses to read more than {@code maxBytes}.
     *
     * @throws SizeLimitExceededException if the stream holds more than {@code maxBytes}
     */
    public static byte[] readLimited(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new SizeLimitExceededException(maxBytes);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Thrown by {@link #readLimited} when the input is larger than allowed. */
    public static final class SizeLimitExceededException extends IOException {

        private final long limit;

        SizeLimitExceededException(long limit) {
            super("Input exceeds the size limit of " + limit + " bytes");
            this.limit = limit;
        }

        public long getLimit() {
            return limit;
        }
    }
}
