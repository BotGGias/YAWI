package de.yawi.installer.core.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E13-S02: merging a document type into an Info.plist. */
class InfoPlistTest {

    private static final String EMPTY_PLIST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\">\n<dict>\n"
            + "\t<key>CFBundleName</key>\n\t<string>SU</string>\n</dict>\n</plist>\n";

    private static AssociationSpec spec() {
        return new AssociationSpec("your-product", ".sumap", Path.of("/Applications/SU.app/Contents/MacOS/su"),
                Path.of("/Applications/SU.app"), Optional.of("YOUR INSTALLER Map"));
    }

    @Test
    void addsTheDocumentTypesKeyWhenAbsent() {
        String merged = InfoPlist.merge(EMPTY_PLIST, spec());
        assertTrue(merged.contains("<key>CFBundleDocumentTypes</key>"), merged);
        assertTrue(merged.contains("<key>CFBundleTypeExtensions</key>"), merged);
        assertTrue(merged.contains("<string>sumap</string>"), merged);
        assertTrue(merged.contains("<string>YOUR INSTALLER Map</string>"), merged);
    }

    @Test
    void isIdempotent() {
        String once = InfoPlist.merge(EMPTY_PLIST, spec());
        assertEquals(once, InfoPlist.merge(once, spec()), "the extension is already declared");
    }

    @Test
    void appendsToAnExistingDocumentTypesArray() {
        String withTypes = "<plist><dict>\n<key>CFBundleDocumentTypes</key>\n<array>\n"
                + "<dict><key>CFBundleTypeExtensions</key><array><string>other</string></array></dict>\n"
                + "</array>\n</dict></plist>\n";
        String merged = InfoPlist.merge(withTypes, spec());
        assertTrue(merged.contains("<string>other</string>"), "the existing type stays");
        assertTrue(merged.contains("<string>sumap</string>"), "the new type is added");
    }
}
