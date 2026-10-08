package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestProblem;
import de.yawi.installer.core.manifest.TestManifests;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholdersTest {

    private final InstallManifest manifest = TestManifests.full();

    private Placeholders full(Set<String> selected) {
        return Placeholders.of(manifest, EngineTestSupport.linux(), Path.of("/opt/su"),
                Map.of("serverPort", "60000", "autostart", "true", "mode", "lan"), selected);
    }

    @Test
    void resolvesEveryBuiltInPlaceholder() {
        Placeholders p = full(Set.of("core", "server"));
        assertEquals("/opt/su/bin", p.resolve("${destination}/bin"));
        assertEquals("v2.4.1", p.resolve("v${product.version}"));
        assertEquals("/tmp/yawi-installer/x", p.resolve("${tempDir}/x"));
        assertEquals("linux-x64", p.resolve("${os}-${arch}"));
        assertEquals("port=60000 auto=true", p.resolve("port=${input.serverPort} auto=${input.autostart}"));
    }

    @Test
    void deselectedComponentsContributeTheirDefaultsNotTheTypedValues() {
        Placeholders p = full(Set.of("core"));
        assertEquals("50000 true lan", p.resolve("${input.serverPort} ${input.autostart} ${input.mode}"),
                "manifest defaults, not the 60000/true/lan the user typed");
        assertEquals("${input.nope}", p.resolve("${input.nope}"), "left untouched, reported by validate");
        List<ManifestProblem> problems = p.validate("step 'x'", "${input.nope} ${nope} ${destination}");
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).message().contains("no such input"), problems.get(0).message());
        assertTrue(problems.get(1).message().contains("unknown placeholder"), problems.get(1).message());
        assertEquals(List.of(), p.validate("s", "${destination}/${os}"));
    }

    @Test
    void selectedInputsWithoutAValueFallBackToTheDefault() {
        Placeholders p = Placeholders.of(manifest, EngineTestSupport.linux(), Path.of("/opt/su"), Map.of(),
                Set.of("core", "server"));
        assertEquals("50000", p.resolve("${input.serverPort}"));
    }

    @Test
    void textWithoutPlaceholdersAndLooseDollarsPassThrough() {
        Placeholders p = full(Set.of("core"));
        assertEquals("C:\\Program Files\\$HOME $1 ${", p.resolve("C:\\Program Files\\$HOME $1 ${"));
        assertEquals(null, p.resolve(null));
        assertEquals(List.of("a", "b.c"), Placeholders.namesIn("${a}/${b.c}"));
    }

    @Test
    void replacementTextIsNotReinterpreted() {
        Placeholders p = Placeholders.ofValues(Map.of("x", "${y}$1\\", "y", "no"));
        assertEquals("${y}$1\\", p.resolve("${x}"));
    }
}
