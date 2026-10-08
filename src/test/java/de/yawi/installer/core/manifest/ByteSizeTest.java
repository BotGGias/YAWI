package de.yawi.installer.core.manifest;

import de.yawi.installer.core.xml.SecureXml;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteSizeTest {

    /** Valid inputs with the expected byte count. */
    static final Map<String, Long> VALID = Map.ofEntries(
            Map.entry("0", 0L),
            Map.entry("1024", 1024L),
            Map.entry("512B", 512L),
            Map.entry("1KB", 1_000L),
            Map.entry("500MB", 500_000_000L),
            Map.entry("2GB", 2_000_000_000L),
            Map.entry("1TB", 1_000_000_000_000L),
            Map.entry("1KiB", 1024L),
            Map.entry("700MiB", 734_003_200L),
            Map.entry("2GiB", 2_147_483_648L),
            Map.entry("1TiB", 1_099_511_627_776L));

    /** Inputs that neither the XSD pattern nor the parser must accept. */
    static final List<String> INVALID = List.of(
            "", " ", "-1", "1.5GB", "700 MiB", "700mib", "1kb", "MiB", "1MiBs", "1 ", "0x10", "1PB");

    @Test
    void parsesAllUnits() {
        VALID.forEach((text, expected) ->
                assertEquals(expected, ByteSize.parse(text), text));
    }

    @Test
    void rejectsMalformedInput() {
        for (String text : INVALID) {
            assertThrows(IllegalArgumentException.class, () -> ByteSize.parse(text), "'" + text + "'");
        }
        assertThrows(IllegalArgumentException.class, () -> ByteSize.parse(null));
    }

    /** The XSD and the parser must accept exactly the same form (E01-S02-T02). */
    @Test
    void xsdPatternMatchesTheParser() throws Exception {
        Document xsd = SecureXml.newDocumentBuilder()
                .parse(ByteSizeTest.class.getResourceAsStream(ManifestParser.SCHEMA_RESOURCE));
        String xsdPattern = null;
        NodeList types = xsd.getElementsByTagNameNS("http://www.w3.org/2001/XMLSchema", "simpleType");
        for (int i = 0; i < types.getLength(); i++) {
            Element type = (Element) types.item(i);
            if (type.getAttribute("name").equals("byteSize")) {
                Element pattern = (Element) type.getElementsByTagNameNS(
                        "http://www.w3.org/2001/XMLSchema", "pattern").item(0);
                xsdPattern = pattern.getAttribute("value");
            }
        }
        assertEquals(ByteSize.PATTERN, xsdPattern, "byteSize pattern in XSD differs from ByteSize.PATTERN");

        Pattern regex = Pattern.compile(xsdPattern);
        for (String text : VALID.keySet()) {
            assertTrue(regex.matcher(text).matches(), "XSD would reject valid '" + text + "'");
        }
        for (String text : INVALID) {
            assertFalse(regex.matcher(text).matches(), "XSD would accept invalid '" + text + "'");
        }
    }

    @Test
    void formatsBinarySizesForHumans() {
        assertEquals("0 B", ByteSize.format(0, Locale.ENGLISH));
        assertEquals("512 B", ByteSize.format(512, Locale.ENGLISH));
        assertEquals("1 KiB", ByteSize.format(1024, Locale.ENGLISH));
        assertEquals("1.5 KiB", ByteSize.format(1536, Locale.ENGLISH));
        assertEquals("1,5 KiB", ByteSize.format(1536, Locale.GERMAN));
        assertEquals("700 MiB", ByteSize.format(ByteSize.parse("700MiB"), Locale.ENGLISH));
        assertEquals("2 GiB", ByteSize.format(ByteSize.parse("2GiB"), Locale.ENGLISH));
        assertEquals("750 MiB", ByteSize.format(ByteSize.parse("750MiB"), Locale.ENGLISH));
        assertEquals("4 TiB", ByteSize.format(ByteSize.parse("4TiB"), Locale.ENGLISH));
        assertEquals("2048 TiB", ByteSize.format(ByteSize.parse("2048TiB"), Locale.ENGLISH));
        assertThrows(IllegalArgumentException.class, () -> ByteSize.format(-1, Locale.ENGLISH));
    }

    @Test
    void rejectsOverflow() {
        assertThrows(IllegalArgumentException.class, () -> ByteSize.parse("99999999999TiB"));
    }
}
