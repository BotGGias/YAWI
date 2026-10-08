package de.yawi.installer.core.answers;

import de.yawi.installer.core.download.ProviderKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E15-S02-T03: what the wizard writes, the reader takes back unchanged. */
class AnswerFileWriterTest {

    @TempDir
    Path tmp;

    @Test
    void roundTripKeepsEveryValueAndOrder() throws IOException {
        Map<String, ProviderKind> sources = new LinkedHashMap<>();
        sources.put("zeta", ProviderKind.TORRENT);
        sources.put("alpha", ProviderKind.BUNDLED);
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("port", "5000");
        inputs.put("name", "Tom & \"Jerry\" <ok>");
        AnswerFile answers = new AnswerFile(Optional.of("su"), Optional.of("de-AT"), Optional.of("/opt/a b"),
                Optional.of(true), Optional.of(List.of("server", "core")), sources, inputs, true, Optional.of(true),
                Optional.of(true));

        Path file = AnswerFileWriter.write(answers, List.of("adminPassword"), tmp.resolve("answers.xml"));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("value=\"Tom &amp; &quot;Jerry&quot; &lt;ok&gt;\""), text);
        assertTrue(text.contains("<!-- input \"adminPassword\" looks like a secret"), text);

        AnswerFile back = AnswerFileReader.read(file);
        assertEquals(answers, back);
        assertEquals(List.of("zeta", "alpha"), List.copyOf(back.sources().keySet()));
        assertEquals(List.of("port", "name"), List.copyOf(back.inputs().keySet()));
    }

    @Test
    void emptyAnswersGiveAMinimalValidFile() throws IOException {
        Path file = AnswerFileWriter.write(AnswerFile.empty(), List.of(), tmp.resolve("empty.xml"));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.contains("<license"), text);
        assertFalse(text.contains("<inputs"), text);
        assertEquals(AnswerFile.empty(), AnswerFileReader.read(file));
    }
}
