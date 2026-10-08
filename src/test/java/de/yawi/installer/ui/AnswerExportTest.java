package de.yawi.installer.ui;

import de.yawi.installer.core.answers.AnswerFile;
import de.yawi.installer.core.answers.AnswerFileReader;
import de.yawi.installer.core.answers.AnswerFileWriter;
import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestParser;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.PlatformFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E15-S02-T03: the wizard's choices become an answer file the silent mode reads back. */
class AnswerExportTest {

    private static final String MANIFEST = "/manifest/full.xml";

    @TempDir
    Path tmp;

    /** full.xml with one input renamed so that it looks like a secret. */
    private static InstallManifest manifest() {
        String xml = new String(TestManifests.bytes(MANIFEST), StandardCharsets.UTF_8)
                .replace("id=\"autostart\" type=\"bool\" default=\"true\"", "id=\"adminToken\" type=\"string\" default=\"\"");
        return new ManifestParser().parse(xml.getBytes(StandardCharsets.UTF_8), TestManifests.origin(MANIFEST));
    }

    @Test
    void exportsTheChoicesWithoutSecretsAndTheReaderTakesThemBack() throws IOException {
        InstallerModel model = new InstallerModel(manifest(), PlatformFactory.current());
        model.setLocale(Locale.GERMAN);
        model.setDestination(tmp.resolve("target"));
        model.setSystemWide(true);
        model.getSelectedComponents().addAll(List.of("server", "core"));
        model.getSourceChoices().put("base-data", ProviderKind.HTTP);
        model.getInputs().put("serverPort", "5000");
        model.getInputs().put("adminToken", "s3cret");
        model.getInputs().put("mode", "internet");

        AnswerFile answers = AnswerExport.of(model);
        assertEquals(Optional.of("your-product"), answers.product());
        assertEquals(Optional.of("de"), answers.language());
        assertEquals(Optional.of(tmp.resolve("target").toString()), answers.destination());
        assertEquals(Optional.of(true), answers.allUsers());
        assertEquals(Optional.of(List.of("core", "server")), answers.components(), "manifest order");
        assertEquals(Map.of("base-data", ProviderKind.HTTP), answers.sources());
        assertEquals(Map.of("serverPort", "5000", "mode", "internet"), answers.inputs());
        assertTrue(answers.licenseAccepted(), "the manifest has a licence and the user got past it");
        assertEquals(List.of("adminToken"), AnswerExport.omitted(model));
        assertEquals("your-product-answers.xml", AnswerExport.defaultFileName(model));

        Path file = AnswerFileWriter.write(answers, AnswerExport.omitted(model), tmp.resolve("answers.xml"));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.contains("s3cret"), text);
        assertTrue(text.contains("adminToken"), "the omission is named: " + text);
        AnswerFile back = AnswerFileReader.read(file);
        assertEquals(answers, back);
        AnswerFileReader.check(back, manifest(), file); // fits its manifest
    }

    @Test
    void untouchedModelExportsTheDefaults() {
        InstallerModel model = new InstallerModel(manifest(), PlatformFactory.current());
        AnswerFile answers = AnswerExport.of(model);
        assertEquals(Optional.of(model.defaultDestination(false).toString()), answers.destination());
        assertEquals(Optional.of(false), answers.allUsers());
        assertEquals(Optional.of(List.of()), answers.components());
        assertEquals(Map.of("base-data", ProviderKind.BUNDLED), answers.sources(), "core is required and uses it");
        assertTrue(answers.inputs().isEmpty());
        assertTrue(AnswerExport.omitted(model).isEmpty());
    }
}
