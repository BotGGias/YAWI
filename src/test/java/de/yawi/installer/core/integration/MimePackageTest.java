package de.yawi.installer.core.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E13-S02: the shared-mime-info package for one extension. */
class MimePackageTest {

    private static AssociationSpec spec(String description) {
        return new AssociationSpec("your-product", ".sumap", Path.of("/opt/su/bin/su"), Path.of("/opt/su"),
                Optional.ofNullable(description));
    }

    @Test
    void rendersTheTypeGlobAndComment() {
        String xml = MimePackage.render(spec("YOUR INSTALLER Map"));
        assertTrue(xml.contains("<mime-type type=\"application/x-vnd.your-product-sumap\">"), xml);
        assertTrue(xml.contains("<glob pattern=\"*.sumap\"/>"), xml);
        assertTrue(xml.contains("<comment>YOUR INSTALLER Map</comment>"), xml);
        assertTrue(xml.startsWith("<?xml"), xml);
    }

    @Test
    void escapesTheComment() {
        String xml = MimePackage.render(spec("A & B <x>"));
        assertTrue(xml.contains("<comment>A &amp; B &lt;x&gt;</comment>"), xml);
        assertFalse(xml.contains("<comment>A & B <x></comment>"), xml);
    }
}
