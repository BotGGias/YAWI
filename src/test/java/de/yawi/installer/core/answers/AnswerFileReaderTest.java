package de.yawi.installer.core.answers;

import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.error.AnswerFileException;
import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.i18n.Messages;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestParser;
import de.yawi.installer.core.manifest.TestManifests;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E15-S02-T01: the answer file is read through the schema and checked against the manifest. */
class AnswerFileReaderTest {

    private static final String MANIFEST = "/manifest/cli/launcher-update.xml";

    @TempDir
    Path tmp;

    private static AnswerFile parse(String resource) {
        return AnswerFileReader.parse(TestManifests.bytes(resource), Path.of(resource));
    }

    private static InstallManifest manifest() {
        String xml = new String(TestManifests.bytes(MANIFEST), StandardCharsets.UTF_8)
                .replace("__VERSION__", "1.0.0").replace("__SHA256__", "0".repeat(64))
                .replace("__BASE__", "http://127.0.0.1:1");
        return new ManifestParser().parse(xml.getBytes(StandardCharsets.UTF_8), TestManifests.origin(MANIFEST));
    }

    @Test
    void readsEveryField() {
        AnswerFile answers = parse("/answers/full.xml");
        assertEquals(Optional.of("su-test-launcher"), answers.product());
        assertEquals(Optional.of("de"), answers.language());
        assertEquals(Optional.of("/tmp/su-answers-test"), answers.destination());
        assertEquals(Optional.of(false), answers.allUsers());
        assertEquals(Optional.of(List.of("core", "extra")), answers.components());
        assertEquals(Map.of("launcher", ProviderKind.HTTP), answers.sources());
        assertEquals(Map.of("port", "5005"), answers.inputs());
        assertTrue(answers.licenseAccepted());
        AnswerFileReader.check(answers, manifest(), Path.of("full.xml")); // fits: no exception
    }

    @Test
    void emptyFileAnswersNothing() {
        AnswerFile answers = AnswerFileReader.parse("<answers version=\"1\"/>".getBytes(StandardCharsets.UTF_8),
                Path.of("empty.xml"));
        assertEquals(AnswerFile.empty(), answers);
        assertFalse(answers.licenseAccepted());
    }

    @Test
    void schemaViolationNamesLineAndElement() {
        AnswerFileException e = assertThrows(AnswerFileException.class, () -> parse("/answers/invalid/no-value.xml"));
        assertEquals(ErrorCode.INVALID_ARGUMENTS, e.code());
        assertEquals(2, e.exitCode());
        assertEquals(1, e.problems().size());
        assertTrue(e.problems().get(0).startsWith("line 4, column "), e.problems().get(0));
        assertTrue(e.problems().get(0).contains("'value'"), e.problems().get(0));
        // The user's text names the file and the problem, not just "invalid argument".
        String user = e.userMessage(Messages.load(), Locale.ENGLISH);
        assertTrue(user.contains("no-value.xml") && user.contains("'value'"), user);
    }

    @Test
    void everySchemaViolationIsReportedAtOnce() {
        AnswerFileException e = assertThrows(AnswerFileException.class, () -> parse("/answers/invalid/unknown-kind.xml"));
        // Xerces reports a bad attribute value twice (facet and attribute); both violations are in the list.
        assertTrue(e.problems().size() >= 2, e.problems().toString());
        assertTrue(e.problems().get(0).startsWith("line 4, column ") && e.problems().get(0).contains("ftp"), e.problems().get(0));
        assertTrue(e.problems().stream().anyMatch(p -> p.startsWith("line 6, column ") && p.contains("maybe")), e.problems().toString());
        assertTrue(e.detail().orElseThrow().contains("\n"));
    }

    @Test
    void otherFormatVersionIsRefused() {
        AnswerFileException e = assertThrows(AnswerFileException.class, () -> parse("/answers/invalid/version-2.xml"));
        assertTrue(e.problems().get(0).contains("format version 2"), e.problems().get(0));
    }

    @Test
    void doctypeIsRefusedBeforeAnythingIsResolved() {
        AnswerFileException e = assertThrows(AnswerFileException.class, () -> parse("/answers/invalid/doctype.xml"));
        assertTrue(e.problems().get(0).startsWith("not well-formed"), e.problems().get(0));
        assertTrue(e.problems().get(0).contains("DOCTYPE"), e.problems().get(0));
    }

    @Test
    void checkAgainstTheManifestListsEveryMismatch() {
        AnswerFile answers = parse("/answers/mismatch.xml");
        AnswerFileException e = assertThrows(AnswerFileException.class,
                () -> AnswerFileReader.check(answers, manifest(), Path.of("mismatch.xml")));
        List<String> problems = e.problems();
        assertEquals(5, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("product=\"other-product\""), problems.get(0));
        assertTrue(problems.get(1).contains("<component id=\"nope\">"), problems.get(1));
        assertTrue(problems.get(2).contains("kind=\"torrent\"") && problems.get(2).contains("no torrent provider"), problems.get(2));
        assertTrue(problems.get(3).contains("<source id=\"ghost\">"), problems.get(3));
        assertTrue(problems.get(4).contains("<input id=\"colour\">"), problems.get(4));
    }

    @Test
    void readsFromDiskAndReportsMissingAndOversizedFiles() throws IOException {
        Path file = tmp.resolve("answers.xml");
        Files.write(file, TestManifests.bytes("/answers/full.xml"));
        assertEquals(parse("/answers/full.xml"), AnswerFileReader.read(file));

        AnswerFileException missing = assertThrows(AnswerFileException.class,
                () -> AnswerFileReader.read(tmp.resolve("nope.xml")));
        assertTrue(missing.problems().get(0).startsWith("cannot be read"), missing.problems().get(0));

        Path big = tmp.resolve("big.xml");
        Files.writeString(big, "<answers version=\"1\"><!--" + " ".repeat((int) AnswerFile.MAX_BYTES) + "--></answers>");
        AnswerFileException tooBig = assertThrows(AnswerFileException.class, () -> AnswerFileReader.read(big));
        assertTrue(tooBig.problems().get(0).contains("larger than"), tooBig.problems().get(0));
    }
}
