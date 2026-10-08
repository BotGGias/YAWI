package de.yawi.installer.core.integration;

/**
 * Merges a {@code CFBundleDocumentTypes} entry into an installed {@code .app}
 * bundle's {@code Info.plist} (macOS). A minimal, dependency-free text
 * merge: idempotent, and a plist it does not understand is left untouched. The
 * bundle is removed whole on uninstall, so no targeted plist reverse is needed.
 */
public final class InfoPlist {

    private InfoPlist() {
    }

    /** The {@code CFBundleDocumentTypes} array entry for this association. */
    public static String documentTypeDict(AssociationSpec spec) {
        return "\t\t<dict>\n"
                + "\t\t\t<key>CFBundleTypeName</key>\n"
                + "\t\t\t<string>" + escape(spec.label()) + "</string>\n"
                + "\t\t\t<key>CFBundleTypeRole</key>\n"
                + "\t\t\t<string>Editor</string>\n"
                + "\t\t\t<key>CFBundleTypeExtensions</key>\n"
                + "\t\t\t<array>\n"
                + "\t\t\t\t<string>" + escape(spec.extensionNoDot()) + "</string>\n"
                + "\t\t\t</array>\n"
                + "\t\t</dict>\n";
    }

    /**
     * Adds the association's document type to {@code plist}. If the extension is
     * already declared the text is returned unchanged; if a
     * {@code CFBundleDocumentTypes} array exists the entry is appended to it,
     * otherwise the key and array are added to the root dictionary.
     */
    public static String merge(String plist, AssociationSpec spec) {
        String marker = "<string>" + escape(spec.extensionNoDot()) + "</string>";
        if (plist.contains(marker)) {
            return plist;
        }
        String entry = documentTypeDict(spec);
        int typesKey = plist.indexOf("<key>CFBundleDocumentTypes</key>");
        if (typesKey >= 0) {
            int array = plist.indexOf("<array>", typesKey);
            if (array >= 0) {
                int insert = array + "<array>".length();
                return plist.substring(0, insert) + "\n" + entry + plist.substring(insert);
            }
        }
        int dict = plist.indexOf("<dict>");
        if (dict >= 0) {
            int insert = dict + "<dict>".length();
            String block = "\n\t<key>CFBundleDocumentTypes</key>\n\t<array>\n" + entry + "\t</array>";
            return plist.substring(0, insert) + block + plist.substring(insert);
        }
        return plist;
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
