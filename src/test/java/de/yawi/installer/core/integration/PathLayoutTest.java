package de.yawi.installer.core.integration;

import de.yawi.installer.core.platform.TestPlatforms;
import de.yawi.installer.core.platform.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E13-S03: which profile files a PATH edit touches per scope. */
class PathLayoutTest {

    @TempDir
    Path home;

    @Test
    void perUserTargetsAreTheProfileAndZshrc() {
        Platform platform = TestPlatforms.scratch(home);
        List<PathLayout.Target> targets = PathLayout.targets(platform, false, "su");
        assertEquals(2, targets.size());
        assertTrue(targets.get(0).path().endsWith(".profile"));
        assertTrue(targets.get(0).createIfMissing(), ".profile is the guaranteed one");
        assertFalse(targets.get(0).installerOwned());
        assertTrue(targets.get(1).path().endsWith(".zshrc"));
        assertFalse(targets.get(1).createIfMissing(), ".zshrc is only touched when it exists");
    }

    @Test
    void systemWideTargetIsAnOwnedDropIn() {
        Platform platform = TestPlatforms.scratch(home);
        List<PathLayout.Target> targets = PathLayout.targets(platform, true, "YOUR INSTALLER");
        assertEquals(1, targets.size());
        assertEquals(Path.of("/etc/profile.d/your-installer.sh"), targets.get(0).path());
        assertTrue(targets.get(0).createIfMissing());
        assertTrue(targets.get(0).installerOwned());
    }
}
