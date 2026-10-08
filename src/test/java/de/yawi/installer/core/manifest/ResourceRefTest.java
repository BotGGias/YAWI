package de.yawi.installer.core.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceRefTest {

    @Test
    void opensClasspathResources() throws IOException {
        try (InputStream in = ResourceRef.open("classpath:/installer.xml")) {
            assertNotNull(in);
            assertTrue(in.read() >= 0);
        }
        // A missing leading slash is tolerated.
        assertTrue(ResourceRef.readText("classpath:installer.xml").contains("<installer"));
    }

    @Test
    void opensFiles(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("eula.txt");
        Files.writeString(file, "Grüße – 日本語");
        assertEquals("Grüße – 日本語", ResourceRef.readText(file.toString()));
        assertFalse(ResourceRef.isClasspath(file.toString()));
        assertTrue(ResourceRef.isClasspath("classpath:/x"));
    }

    @Test
    void missingResourcesThrow() {
        assertThrows(NoSuchFileException.class, () -> ResourceRef.open("classpath:/does/not/exist"));
        assertThrows(IOException.class, () -> ResourceRef.open("/does/not/exist/eula.txt"));
    }
}
