package de.yawi.installer.core.integration;

import de.yawi.installer.core.manifest.ResourceRef;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PngHeaderTest {

    @Test
    void readsTheSizeOfTheBundledIcon() throws Exception {
        byte[] bytes;
        try (InputStream in = ResourceRef.open("classpath:/img/icon.png")) {
            bytes = in.readAllBytes();
        }
        assertArrayEquals(new int[] {256, 256}, PngHeader.size(bytes).orElseThrow());
    }

    @Test
    void rejectsAnythingThatIsNotAPng() {
        assertTrue(PngHeader.size("not a png at all, just some text bytes".getBytes()).isEmpty());
        assertTrue(PngHeader.size(new byte[10]).isEmpty());
        assertTrue(PngHeader.size(null).isEmpty());
        assertTrue(Optional.empty().equals(PngHeader.size(new byte[0])));
    }
}
