package de.yawi.installer.core.xml;

/**
 * Escaping for the XML files the installer writes by hand (the record, the
 * answer file); the counterpart of {@link SecureXml} for the writing side.
 */
public final class XmlText {

    private XmlText() {
    }

    /**
     * Text safe inside a double-quoted attribute or as element content;
     * control characters (from process output in messages) become spaces.
     */
    public static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\n', '\r', '\t' -> sb.append(' ');
                default -> sb.append(c < 0x20 ? ' ' : c);
            }
        }
        return sb.toString();
    }
}
