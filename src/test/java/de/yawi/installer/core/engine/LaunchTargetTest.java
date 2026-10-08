package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestParser;
import de.yawi.installer.core.manifest.TestManifests;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E13: what the finish page and {@code --relaunch} start. */
class LaunchTargetTest {

    private static InstallManifest bundledWith(String target) {
        String xml = new String(TestManifests.bytes("/installer.xml"), StandardCharsets.UTF_8)
                .replace("<target>${destination}/your-product-launcher.AppImage</target>", "<target>" + target + "</target>");
        return new ManifestParser().parse(xml.getBytes(StandardCharsets.UTF_8), TestManifests.origin("/installer.xml"));
    }

    @Test
    void theFirstShortcutsTargetWithPlaceholdersResolved() {
        Path dest = Path.of("/opt/su").toAbsolutePath();
        assertEquals(dest.resolve("your-product-launcher.AppImage"),
                LaunchTarget.of(TestManifests.bundled(), EngineTestSupport.linux(), dest).orElseThrow());
    }

    @Test
    void nothingWithoutAShortcutOrWithATargetOutsideTheDestination() {
        Path dest = Path.of("/opt/su").toAbsolutePath();
        InstallManifest none = new ManifestParser().parse(TestManifests.bytes("/manifest/engine/file-steps.xml"),
                TestManifests.origin("/manifest/engine/file-steps.xml"));
        assertTrue(none.integration().shortcuts().isEmpty());
        assertTrue(LaunchTarget.of(none, EngineTestSupport.linux(), dest).isEmpty());
        assertTrue(LaunchTarget.of(bundledWith("${destination}/../../bin/sh"), EngineTestSupport.linux(), dest).isEmpty());
        assertTrue(LaunchTarget.of(bundledWith("/bin/sh"), EngineTestSupport.linux(), dest).isEmpty());
    }
}
