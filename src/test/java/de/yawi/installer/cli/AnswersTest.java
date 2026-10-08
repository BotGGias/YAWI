package de.yawi.installer.cli;

import de.yawi.installer.core.answers.AnswerFile;
import de.yawi.installer.core.download.ProviderKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** command line over answer file over nothing. */
class AnswersTest {

    private static final AnswerFile FILE = new AnswerFile(Optional.of("su"), Optional.of("de"), Optional.of("/opt/file"),
            Optional.of(true), Optional.of(List.of("core", "extra")), Map.of("launcher", ProviderKind.HTTP),
            Map.of("port", "5005", "name", "file"), true, Optional.empty(), Optional.empty());

    @Test
    void fileAnswersWhatTheCommandLineLeavesOpen() {
        Answers answers = Answers.merge(Arguments.parse("--silent", "--config=a.xml"), Optional.of(FILE));
        assertEquals(Optional.of("de"), answers.lang());
        assertEquals(Optional.of("/opt/file"), answers.dest());
        assertTrue(answers.allUsers());
        assertEquals(List.of("core", "extra"), answers.components());
        assertEquals(Map.of("port", "5005", "name", "file"), answers.inputs());
        assertEquals(Map.of("launcher", ProviderKind.HTTP), answers.sources());
        assertTrue(answers.licenseAccepted());
        assertTrue(answers.inputFromFile(Arguments.parse("--silent", "--config=a.xml"), "port"));
    }

    @Test
    void commandLineWinsOverTheFile() {
        Arguments args = Arguments.parse("--silent", "--config=a.xml", "--lang=en", "--dest=/opt/cli",
                "--components=core", "--input.port=6000");
        Answers answers = Answers.merge(args, Optional.of(FILE));
        assertEquals(Optional.of("en"), answers.lang());
        assertEquals(Optional.of("/opt/cli"), answers.dest());
        assertEquals(List.of("core"), answers.components());
        assertEquals("6000", answers.inputs().get("port"));
        assertEquals("file", answers.inputs().get("name"), "unrelated file inputs stay");
        assertFalse(answers.inputFromFile(args, "port"));
        assertTrue(answers.inputFromFile(args, "name"));
    }

    @Test
    void withoutAFileEverythingComesFromTheArgumentsOrStaysOpen() {
        Answers answers = Answers.merge(Arguments.parse("--silent", "--accept-license", "--all-users"), Optional.empty());
        assertTrue(answers.lang().isEmpty());
        assertTrue(answers.dest().isEmpty());
        assertTrue(answers.allUsers());
        assertTrue(answers.components().isEmpty());
        assertTrue(answers.sources().isEmpty());
        assertTrue(answers.licenseAccepted());
        assertTrue(answers.fromFile().isEmpty());
    }

    @Test
    void blankDestinationInTheFileCountsAsNotGiven() {
        AnswerFile blank = new AnswerFile(Optional.empty(), Optional.empty(), Optional.of("  "), Optional.of(true),
                Optional.empty(), Map.of(), Map.of(), false, Optional.empty(), Optional.empty());
        Answers answers = Answers.merge(Arguments.parse("--silent"), Optional.of(blank));
        assertTrue(answers.dest().isEmpty());
        assertTrue(answers.allUsers(), "the scope still applies to the default folder");
        assertFalse(answers.licenseAccepted());
    }
}
