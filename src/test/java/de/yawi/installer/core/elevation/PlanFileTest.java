package de.yawi.installer.core.elevation;

import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.manifest.ManifestOrigin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanFileTest {

    @TempDir
    Path tmp;

    static ElevationPlan sample(Path base) {
        return new ElevationPlan(ElevationNeed.Mode.PARTIAL, base.resolve("dest dir"), Instant.parse("2026-09-14T10:00:00Z"),
                Set.of("core", "extra"), Map.of("adminPassword", "p\"a<s&s'w>ord", "port", "8080"),
                List.of("write", "service"),
                Map.of("data", ElevationPlan.Artifact.file(ProviderKind.HTTP, base.resolve("dl").resolve("data.zip")),
                        "icons", ElevationPlan.Artifact.classpath("classpath:/icons.zip")),
                new ManifestOrigin(ManifestOrigin.Kind.EXPLICIT_URL, "https://x/installer.xml",
                        ManifestOrigin.Signature.VERIFIED),
                Map.of("HOME", "/home/me", "PATH", "/usr/bin:/bin"), Map.of("user.home", "/home/me", "java.io.tmpdir", "/tmp"),
                Optional.of("me"), Optional.of("users"), true);
    }

    @Test
    void roundTripKeepsEverythingIncludingSecretsAndSpecialCharacters() throws IOException {
        ElevationPlan plan = sample(tmp);
        Path file = tmp.resolve("plan.xml");
        PlanFile.write(plan, file);
        ElevationPlan read = PlanFile.read(file);
        assertEquals(plan, read);
        assertTrue(Files.readString(file).contains("&quot;"), "attributes are escaped");
    }

    @Test
    void ownerLessWindowsStylePlanRoundTrips() throws IOException {
        ElevationPlan plan = new ElevationPlan(ElevationNeed.Mode.WHOLE, tmp.resolve("d"), Instant.EPOCH, Set.of("core"),
                Map.of(), List.of("a"), Map.of(), new ManifestOrigin(ManifestOrigin.Kind.CLASSPATH, "/installer.xml"),
                Map.of("USERPROFILE", "C:\\Users\\me"), Map.of(), Optional.empty(), Optional.empty(), false);
        Path file = tmp.resolve("plan.xml");
        PlanFile.write(plan, file);
        assertEquals(plan, PlanFile.read(file));
    }

    @Test
    void brokenOrForeignFilesAreRefused() throws IOException {
        Path file = tmp.resolve("plan.xml");
        Files.writeString(file, "<elevation version=\"1\" mode=\"WHOLE\">");
        assertThrows(IOException.class, () -> PlanFile.read(file));
        Files.writeString(file, "<other version=\"1\"/>");
        assertThrows(IOException.class, () -> PlanFile.read(file));
        Files.writeString(file, "<elevation version=\"2\" mode=\"WHOLE\" destination=\"/x\" startedAt=\"2026-01-01T00:00:00Z\""
                + " chownDestination=\"false\"><origin kind=\"CLASSPATH\" location=\"a\" signature=\"UNSIGNED\"/></elevation>");
        assertThrows(IOException.class, () -> PlanFile.read(file));
        Files.writeString(file, "<elevation version=\"1\" mode=\"SIDEWAYS\" destination=\"/x\" startedAt=\"2026-01-01T00:00:00Z\""
                + " chownDestination=\"false\"><origin kind=\"CLASSPATH\" location=\"a\" signature=\"UNSIGNED\"/></elevation>");
        assertThrows(IOException.class, () -> PlanFile.read(file));
    }

    @Test
    void handoverDirectoryIsPrivateAndHasTheChildsFilesReady() throws IOException {
        Path dir = HandoverDir.create(tmp.resolve("work"));
        assertTrue(Files.isRegularFile(dir.resolve(HandoverDir.EVENTS)));
        assertTrue(Files.isRegularFile(dir.resolve(HandoverDir.RESULT)));
        assertTrue(Files.isRegularFile(dir.resolve(HandoverDir.LOG)));
        if (HandoverDir.posix()) {
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(dir)));
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(dir.resolve(HandoverDir.EVENTS))));
        }
        HandoverDir.delete(dir);
        assertTrue(Files.notExists(dir));
    }
}
