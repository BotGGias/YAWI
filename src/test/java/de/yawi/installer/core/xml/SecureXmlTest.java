package de.yawi.installer.core.xml;

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXParseException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureXmlTest {

    private static InputStream resource(String name) {
        InputStream in = SecureXmlTest.class.getResourceAsStream("/manifest/security/" + name);
        assertNotNull(in, "missing test resource " + name);
        return in;
    }

    @Test
    void rejectsAnyDoctype() throws Exception {
        try (InputStream in = resource("doctype.xml")) {
            SAXParseException e = assertThrows(SAXParseException.class,
                    () -> SecureXml.newDocumentBuilder().parse(in));
            assertTrue(e.getMessage().contains("DOCTYPE"), e.getMessage());
            assertTrue(e.getLineNumber() > 0, "position expected");
        }
    }

    @Test
    void rejectsExternalEntities() throws Exception {
        try (InputStream in = resource("xxe-external-entity.xml")) {
            assertThrows(SAXParseException.class, () -> SecureXml.newDocumentBuilder().parse(in));
        }
    }

    @Test
    void rejectsEntityRecursion() throws Exception {
        try (InputStream in = resource("billion-laughs.xml")) {
            assertThrows(SAXParseException.class, () -> SecureXml.newDocumentBuilder().parse(in));
        }
    }

    @Test
    void staxReaderRejectsDoctype() throws Exception {
        XMLInputFactory factory = SecureXml.newXMLInputFactory();
        try (InputStream in = resource("xxe-external-entity.xml")) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            assertThrows(XMLStreamException.class, () -> {
                while (reader.hasNext()) {
                    reader.next();
                }
            });
        }
    }

    @Test
    void parsesPlainDocuments() throws Exception {
        byte[] xml = "<a><b/></a>".getBytes(StandardCharsets.UTF_8);
        assertEquals("a", SecureXml.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml)).getDocumentElement().getTagName());
    }

    @Test
    void readLimitedReturnsEverythingBelowTheLimit() throws Exception {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(data, SecureXml.readLimited(new ByteArrayInputStream(data), 5));
    }

    @Test
    void readLimitedRefusesOversizedInput() {
        byte[] data = new byte[10_000];
        SecureXml.SizeLimitExceededException e = assertThrows(
                SecureXml.SizeLimitExceededException.class,
                () -> SecureXml.readLimited(new ByteArrayInputStream(data), 9_999));
        assertEquals(9_999, e.getLimit());
    }
}
