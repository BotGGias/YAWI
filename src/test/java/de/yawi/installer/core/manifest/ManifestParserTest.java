package de.yawi.installer.core.manifest;

import de.yawi.installer.core.platform.OperatingSystem;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestParserTest {

    private static final ManifestOrigin ORIGIN = TestManifests.origin("test");

    @Test
    void readsTheFullExample() {
        InstallManifest m = TestManifests.full();

        assertEquals(1, m.schemaVersion());
        assertEquals(ManifestOrigin.Kind.CLASSPATH, m.origin().kind());

        assertEquals("your-product", m.product().id());
        assertEquals("2.4.1", m.product().version());
        assertEquals("YOUR INSTALLER", m.product().name());
        assertEquals("https://example.invalid", m.product().homepage());
        assertEquals("classpath:/img/icon.png", m.product().icon());

        assertEquals(Locale.ENGLISH, m.languages().defaultLanguage());
        assertEquals(8, m.languages().languages().size());
        assertEquals(Locale.forLanguageTag("zh"), m.languages().languages().get(5));

        assertEquals(List.of("welcome", "license", "components", "destination",
                "source", "summary", "progress", "finish"), m.wizard().pages());
        assertEquals("classpath:/license/eula.txt", m.wizard().license());
        assertEquals(820, m.wizard().width());
        // E05-S05: options and required on inputs
        Component server = m.component("server").orElseThrow();
        assertEquals(3, server.inputs().size());
        ComponentInput mode = server.inputs().get(2);
        assertEquals("mode", mode.id());
        assertEquals(ComponentInput.InputType.CHOICE, mode.type());
        assertTrue(mode.required());
        assertEquals(List.of(new ComponentInput.Option("lan", "LAN only"),
                new ComponentInput.Option("internet", "Internet")), mode.options());
        assertFalse(server.inputs().get(0).required());
        assertTrue(server.inputs().get(0).options().isEmpty());
        assertEquals(540, m.wizard().height());

        assertTrue(m.destination().allowUserChange());
        assertEquals(2L << 30, m.destination().minFreeBytes());
        assertEquals("%LOCALAPPDATA%\\your-installer", m.destination().defaults().get(OperatingSystem.WINDOWS));
        assertEquals("/opt/your-product", m.destination().systemWide().get(OperatingSystem.LINUX));
    }

    @Test
    void readsSourcesWithAllProviderTypes() {
        Source source = TestManifests.full().source("base-data").orElseThrow();

        assertEquals(700L << 20, source.sizeBytes());
        assertEquals("0".repeat(64), source.sha256());
        assertEquals(URI.create("https://example.invalid/dl/latest.json"), source.versionCheckUrl());
        assertEquals(3, source.providers().size());

        Provider.Bundled bundled = assertInstanceOf(Provider.Bundled.class, source.providers().get(0));
        assertEquals("classpath:/payload/base-data.zip", bundled.path());
        Provider.Http http = assertInstanceOf(Provider.Http.class, source.providers().get(1));
        assertEquals(2, http.urls().size());
        Provider.Torrent torrent = assertInstanceOf(Provider.Torrent.class, source.providers().get(2));
        assertTrue(torrent.magnet().startsWith("magnet:?xt=urn:btih:"));
        assertTrue(torrent.torrentFileOrEmpty().isEmpty());
        // E08 attributes: the ones given, defaults for the rest
        assertEquals(6899, torrent.port());
        assertEquals(java.time.Duration.ofMinutes(2), torrent.peerTimeout());
        assertEquals(Provider.Torrent.Fallback.ASK, torrent.fallback());
        assertFalse(torrent.lsd());
        assertTrue(torrent.pex());
        Provider.Torrent defaults = new Provider.Torrent("magnet:?xt=urn:btih:00", null);
        assertEquals(Provider.Torrent.DEFAULT_PORT, defaults.port());
        assertEquals(Provider.Torrent.DEFAULT_PEER_TIMEOUT, defaults.peerTimeout());
        assertEquals(Provider.Torrent.Fallback.AUTO, defaults.fallback());
    }

    @Test
    void readsComponentsPresetsAndInputs() {
        InstallManifest m = TestManifests.full();
        assertEquals(2, m.components().size());

        Component core = m.component("core").orElseThrow();
        assertTrue(core.required());
        assertTrue(core.selectedByDefault());
        assertEquals(List.of("base-data"), core.uses().stream().map(OsRef::ref).toList());
        assertEquals(List.of("unpack-core", "configure-core"),
                core.stepRefs().stream().map(OsRef::ref).toList());

        Component server = m.component("server").orElseThrow();
        assertFalse(server.required());
        assertEquals(50L << 20, server.sizeBytes());
        assertEquals(List.of("core"), server.dependsOn());
        assertEquals(3, server.inputs().size());
        ComponentInput port = server.inputs().get(0);
        assertEquals("serverPort", port.id());
        assertEquals(ComponentInput.InputType.INT, port.type());
        assertEquals("50000", port.defaultValue());
        assertEquals(1024L, port.min());
        assertEquals(65535L, port.max());
        assertEquals("Port", port.label());

        assertEquals(List.of("core"), m.preset("typical").orElseThrow().componentRefs());
        assertEquals(List.of("core", "server"), m.preset("full").orElseThrow().componentRefs());
    }

    @Test
    void readsAllStepTypes() {
        InstallManifest m = TestManifests.full();
        assertEquals(4, m.steps().size());

        InstallStep.Extract extract = assertInstanceOf(InstallStep.Extract.class, m.step("unpack-core").orElseThrow());
        assertEquals(60, extract.weight());
        assertEquals("base-data", extract.archiveRef());
        assertEquals("${destination}", extract.to());

        InstallStep.Template template = assertInstanceOf(InstallStep.Template.class, m.step("configure-core").orElseThrow());
        assertEquals(Map.of("INSTALL_DIR", "${destination}", "PORT", "${input.serverPort}"), template.replacements());

        InstallStep.Copy copy = assertInstanceOf(InstallStep.Copy.class, m.step("install-server").orElseThrow());
        assertEquals("${destination}/server", copy.from());

        InstallStep.RunCommand run = assertInstanceOf(InstallStep.RunCommand.class, m.step("register-service").orElseThrow());
        assertEquals(10, run.weight());
        assertEquals(0, run.expectExitCode());
        assertEquals(120, run.timeoutSeconds());
        assertEquals(InstallStep.OnFailure.ABORT, run.onFailure());
        assertFalse(run.elevated());
        assertEquals("${destination}", run.workingDir());
        assertEquals(Map.of("SU_HOME", "${destination}"), run.env());
        assertEquals(3, run.commands().size());
        assertEquals(2, run.commandsFor(OperatingSystem.LINUX).size());
        assertEquals(List.of("chmod", "+x", "${destination}/bin/su-server"),
                run.commandsFor(OperatingSystem.LINUX).get(0).args());
        assertEquals("cmd", run.commandsFor(OperatingSystem.WINDOWS).get(0).executable());
        assertEquals(1, run.rollbackFor(OperatingSystem.LINUX).size());
        assertTrue(run.rollbackFor(OperatingSystem.WINDOWS).isEmpty());
    }

    @Test
    void readsIntegrationAndMessages() {
        InstallManifest m = TestManifests.full();

        assertEquals(1, m.integration().shortcuts().size());
        IntegrationConfig.Shortcut shortcut = m.integration().shortcuts().get(0);
        assertEquals("main-shortcut", shortcut.id());
        assertTrue(shortcut.desktop() && shortcut.menu());
        assertEquals(List.of("${destination}/bin"), m.integration().pathEntries());
        assertEquals(".sumap", m.integration().fileAssociations().get(0).extension());

        assertEquals(2, m.messages().size());
        assertEquals("Dedizierter Server", m.messages().get(Locale.GERMAN).get("components.server.name"));
        assertEquals("Server component for hosting your own games.",
                m.messages().get(Locale.ENGLISH).get("components.server.description"));
    }

    @Test
    void collectsAllSchemaErrorsWithPositions() {
        ManifestException e = assertThrows(ManifestException.class,
                () -> TestManifests.parse("/manifest/invalid/two-schema-errors.xml"));

        // Xerces reports an invalid attribute twice (pattern + attribute), so
        // count distinct positions rather than problems.
        for (ManifestProblem p : e.getErrors()) {
            assertTrue(p.hasPosition(), "position missing: " + p);
            assertTrue(p.column() > 0, "column missing: " + p);
        }
        assertEquals(java.util.Set.of(6, 8),
                e.getErrors().stream().map(ManifestProblem::line).collect(java.util.stream.Collectors.toSet()),
                "minFreeBytes on line 6 and required on line 8: " + e.getMessage());
        assertTrue(e.getMessage().contains("line 6:"), e.getMessage());
    }

    @Test
    void danglingReferencesAreCaughtByTheSchema() {
        ManifestException e = assertThrows(ManifestException.class,
                () -> TestManifests.parse("/manifest/invalid/dangling-ref.xml"));

        assertEquals(3, e.getErrors().size(), e.getMessage());
        assertTrue(e.getErrors().stream().allMatch(ManifestProblem::hasPosition));
        // Xerces names the failing keyref, e.g. "cvc-identity-constraint.4.3: ... 'dependsOnRef'"
        String all = e.getMessage();
        assertTrue(all.contains("dependsOnRef") && all.contains("usesRef") && all.contains("componentStepRef"), all);
    }

    @Test
    void reportsMalformedXmlWithPosition() {
        byte[] broken = "<installer schemaVersion=\"1\">\n  <product>\n</installer>".getBytes(StandardCharsets.UTF_8);
        ManifestException e = assertThrows(ManifestException.class, () -> new ManifestParser().parse(broken, ORIGIN));

        assertEquals(1, e.getProblems().size());
        assertEquals(3, e.getProblems().get(0).line());
    }

    @Test
    void rejectsDoctypeBeforeValidating() {
        ManifestException e = assertThrows(ManifestException.class,
                () -> TestManifests.parse("/manifest/security/xxe-external-entity.xml"));
        assertTrue(e.getMessage().contains("DOCTYPE"), e.getMessage());
    }

    @Test
    void ignoresTheSchemaLocationHintOfTheManifest() {
        // Would fail (connection refused) if the hint were followed.
        InstallManifest m = TestManifests.parse("/manifest/security/external-schema.xml");
        assertEquals("p", m.product().id());
    }

    @Test
    void rejectsOversizedManifests() {
        byte[] huge = new byte[(int) ManifestParser.MAX_MANIFEST_BYTES + 1];
        ManifestException e = assertThrows(ManifestException.class,
                () -> new ManifestParser().parse(new java.io.ByteArrayInputStream(huge), ORIGIN));
        assertTrue(e.getMessage().contains("larger than"), e.getMessage());
    }
}
