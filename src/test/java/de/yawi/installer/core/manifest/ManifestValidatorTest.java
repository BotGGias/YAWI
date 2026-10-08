package de.yawi.installer.core.manifest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One negative manifest per rule under src/test/resources/manifest/invalid/. */
class ManifestValidatorTest {

    private static ManifestException reject(String file) {
        return assertThrows(ManifestException.class, () -> TestManifests.parse("/manifest/invalid/" + file));
    }

    private static ManifestProblem single(ManifestException e) {
        assertEquals(1, e.getProblems().size(), e.getMessage());
        return e.getProblems().get(0);
    }

    @Test
    void theFullExampleIsClean() {
        assertTrue(new ManifestValidator().validate(TestManifests.full()).isEmpty());
    }

    @Test
    void choiceInputNeedsOptions() {
        ManifestProblem p = single(reject("choice-without-options.xml"));
        assertTrue(p.isError());
        assertEquals("input 'mode' of component 'core'", p.subject());
        assertTrue(p.message().contains("<option>"), p.message());
    }

    @Test
    void choiceDefaultMustBeAnOption() {
        ManifestProblem p = single(reject("input-default-not-option.xml"));
        assertTrue(p.isError());
        assertTrue(p.message().contains("\"wan\""), p.message());
    }

    @Test
    void inputBoundsMustBeOrdered() {
        ManifestProblem p = single(reject("input-min-greater-max.xml"));
        assertTrue(p.isError());
        assertTrue(p.message().contains("2000") && p.message().contains("1000"), p.message());
    }

    @Test
    void inputIdsAreGlobal() {
        // ${input.<id>} is global, so the XSD's inputKey rejects a second 'port'.
        ManifestException e = reject("duplicate-input-id.xml");
        assertTrue(e.getMessage().contains("inputKey"), e.getMessage());
        assertTrue(single(e).isError());
    }

    @Test
    void unknownSchemaVersion() {
        ManifestProblem p = single(reject("schema-version-2.xml"));
        assertTrue(p.isError());
        assertTrue(p.message().contains("schema version 2"), p.message());
        assertTrue(p.message().contains("supported: [1]"), p.message());
    }

    @Test
    void dependencyCycleIsNamed() {
        ManifestProblem p = single(reject("dependson-cycle.xml"));
        assertTrue(p.isError());
        assertTrue(p.subject().contains("server") || p.subject().contains("tools"), p.subject());
        assertTrue(p.message().contains("server -> tools -> server")
                || p.message().contains("tools -> server -> tools"), p.message());
    }

    @Test
    void selfDependencyIsACycle() {
        ManifestProblem p = single(reject("dependson-self.xml"));
        assertEquals("component 'core'", p.subject());
        assertTrue(p.message().contains("core -> core"), p.message());
    }

    @Test
    void nothingWouldBeInstalled() {
        ManifestProblem p = single(reject("no-required-component.xml"));
        assertTrue(p.isError());
        assertTrue(p.message().contains("typical"), p.message());
    }

    @Test
    void sourceWithoutProvider() {
        ManifestProblem p = single(reject("source-without-provider.xml"));
        assertEquals("source 'data'", p.subject());
        assertTrue(p.message().contains("no <provider>"), p.message());
    }

    @Test
    void torrentAttributesOnOtherProvidersAreRejected() {
        ManifestProblem p = single(reject("torrent-attribute-on-http.xml"));
        assertEquals("source 'data'", p.subject());
        assertTrue(p.message().contains("'peerTimeout' only applies to type=\"torrent\""), p.message());
    }

    @Test
    void unknownPageName() {
        ManifestProblem p = single(reject("unknown-page.xml"));
        assertEquals("page 'licence'", p.subject());
        assertTrue(p.message().contains("license"), "should list the known pages: " + p.message());
    }

    /** E14-S02: the maintenance page is the wizard's own, in front of whatever the manifest lists. */
    @Test
    void maintenancePageIsReserved() {
        ManifestProblem p = single(reject("reserved-page.xml"));
        assertEquals("page 'maintenance'", p.subject());
        assertTrue(p.message().contains("reserved"), p.message());
    }

    /** E14-S03: so is the uninstall page. */
    @Test
    void uninstallPageIsReserved() {
        ManifestProblem p = single(reject("reserved-page-uninstall.xml"));
        assertEquals("page 'uninstall'", p.subject());
        assertTrue(p.message().contains("reserved"), p.message());
    }

    @Test
    void httpWithoutChecksumIsOnlyAWarning() {
        InstallManifest m = TestManifests.parse("/manifest/invalid/http-without-sha256.xml");
        List<ManifestProblem> problems = new ManifestValidator().validate(m);

        assertEquals(1, problems.size(), problems.toString());
        assertEquals(ManifestProblem.Severity.WARNING, problems.get(0).severity());
        assertEquals("source 'data'", problems.get(0).subject());
    }

    @Test
    void plainHttpUrlIsFlaggedAsInsecure() {
        InstallManifest m = TestManifests.parse("/manifest/download/plain-http.xml");
        List<ManifestProblem> problems = new ManifestValidator().validate(m);
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(ManifestProblem.Severity.WARNING, problems.get(0).severity());
        assertTrue(problems.get(0).message().contains("plain http"), problems.get(0).message());
        assertTrue(problems.get(0).message().contains("http://mirror.invalid/data.zip"), problems.get(0).message());
    }

    @Test
    void stepsMissingTheirTypeSpecificChildren() {
        ManifestException e = reject("step-without-required-children.xml");
        assertEquals(4, e.getErrors().size(), e.getMessage());
        assertEquals("step 'unpack'", e.getErrors().get(0).subject());
        assertTrue(e.getErrors().get(0).message().contains("<archive ref>"));
        assertEquals("step 'run'", e.getErrors().get(1).subject());
        assertTrue(e.getErrors().get(1).message().contains("<commands os>"));
        assertEquals("step 'mk'", e.getErrors().get(2).subject());
        assertTrue(e.getErrors().get(2).message().contains("<to>"));
        assertEquals("step 'mode'", e.getErrors().get(3).subject());
        assertTrue(e.getErrors().get(3).message().contains("@mode"));
    }

    @Test
    void allBusinessErrorsAreReportedTogether() {
        ManifestException e = reject("three-errors.xml");
        List<String> subjects = e.getErrors().stream().map(ManifestProblem::subject).toList();

        assertEquals(List.of("component 'core'", "source 'data'", "page 'bogus'"), subjects, e.getMessage());
        assertFalse(e.getMessage().contains("line "), "business problems name ids, not positions");
        assertTrue(e.getMessage().startsWith("Manifest /manifest/invalid/three-errors.xml is invalid\n  [ERROR] "));
    }
}
