package de.yawi.installer.core.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** E13-S02: the product-unique names derived for a file association. */
class AssociationSpecTest {

    private static AssociationSpec spec(String productId, String extension, String description) {
        return new AssociationSpec(productId, extension, Path.of("/opt/su/bin/su"), Path.of("/opt/su"),
                Optional.ofNullable(description));
    }

    @Test
    void derivesTheMimeTypeProgIdAndBaseName() {
        AssociationSpec spec = spec("your-product", ".sumap", "YOUR INSTALLER Map");
        assertEquals("sumap", spec.extensionNoDot());
        assertEquals("application/x-vnd.your-product-sumap", spec.mimeType());
        assertEquals("your-installer.sumap", spec.progId());
        assertEquals("your-product-sumap", spec.baseName());
        assertEquals("YOUR INSTALLER Map", spec.label());
    }

    @Test
    void staticDerivationsMatchTheInstanceOnes() {
        assertEquals("application/x-vnd.your-product-sumap", AssociationSpec.mimeType("your-product", ".sumap"));
        assertEquals("your-installer.sumap", AssociationSpec.progId("your-product", ".sumap"));
        assertEquals("your-product-sumap", AssociationSpec.baseName("your-product", ".sumap"));
    }

    @Test
    void sanitisesTheProductIdForTheMediaTypeAndProgId() {
        AssociationSpec spec = spec("My_App", ".Map", null);
        assertEquals("application/x-vnd.my-app-map", spec.mimeType());
        assertEquals("MyApp.Map", spec.progId());
        // No description: the product id and the extension.
        assertEquals("My_App .Map", spec.label());
    }
}
