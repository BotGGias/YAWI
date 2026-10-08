package de.yawi.installer.core.integration;

/**
 * Renders a freedesktop shared-mime-info package for one file extension
 * No manifest command is embedded - only the extension's
 * glob and a comment, the same security posture as {@link DesktopEntry}.
 */
public final class MimePackage {

    private MimePackage() {
    }

    public static String render(AssociationSpec spec) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<mime-info xmlns=\"http://www.freedesktop.org/standards/shared-mime-info\">\n"
                + "  <mime-type type=\"" + escape(spec.mimeType()) + "\">\n"
                + "    <comment>" + escape(spec.label()) + "</comment>\n"
                + "    <glob pattern=\"*." + escape(spec.extensionNoDot()) + "\"/>\n"
                + "  </mime-type>\n"
                + "</mime-info>\n";
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
