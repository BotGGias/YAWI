package de.yawi.installer.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * {@code --result=<file>}: one JSON object the caller reads after the
 * installer ended, whatever way it ended (update contract):
 * {@code {"exitCode":0,"version":"1.2.3","finishedAt":"2026-09-13T18:00:00Z","message":"…"}}.
 * Written by hand - the project has no JSON library and needs none for four
 * fields.
 */
public final class ResultFile {

    private static final Logger LOG = LoggerFactory.getLogger(ResultFile.class);

    private ResultFile() {
    }

    /** Best effort: a result that cannot be written is logged, the exit code still tells. */
    public static void write(Path file, int exitCode, String version, Instant finishedAt, String message) {
        String json = json(exitCode, version, finishedAt, message);
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.writeString(file, json + "\n", StandardCharsets.UTF_8);
            LOG.info("Result written to {}: {}", file, json);
        } catch (IOException | SecurityException e) {
            LOG.error("Result file {} could not be written: {}", file, e.toString());
        }
    }

    static String json(int exitCode, String version, Instant finishedAt, String message) {
        return "{\"exitCode\":" + exitCode
                + ",\"version\":\"" + escape(version == null ? "" : version) + "\""
                + ",\"finishedAt\":\"" + DateTimeFormatter.ISO_INSTANT.format(finishedAt) + "\""
                + ",\"message\":\"" + escape(message == null ? "" : message) + "\"}";
    }

    /** The JSON string escapes; anything below U+0020 as {@code \\uXXXX}. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
