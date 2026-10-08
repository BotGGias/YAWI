package de.yawi.installer.core.integrity;

import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.IntegrityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E10-S01-T01: the digest runs over the bytes as they are written; a mismatch is an integrity failure. */
class ChecksumVerifierTest {

    private static final byte[] DATA = "the quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tmp;

    private static String sha256(byte[] data) throws Exception {
        return ChecksumVerifier.hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void matchingDigestPassesAndUppercaseIsAccepted() throws Exception {
        ChecksumVerifier v = ChecksumVerifier.sha256(sha256(DATA).toUpperCase());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (OutputStream out = v.wrap(sink)) {
            out.write(DATA);
        }
        assertArrayEquals(DATA, sink.toByteArray(), "the wrapped stream passes everything through");
        v.verify("data.bin");
    }

    @Test
    void mismatchIsAnIntegrityFailureNamingTheFile() throws Exception {
        ChecksumVerifier v = ChecksumVerifier.sha256(sha256(DATA));
        try (OutputStream out = v.wrap(new ByteArrayOutputStream())) {
            out.write(DATA, 0, DATA.length - 1);
        }
        IntegrityException e = assertThrows(IntegrityException.class, () -> v.verify("data.bin"));
        assertEquals(ErrorCode.INTEGRITY_FAILED, e.code());
        assertEquals("data.bin", e.fileName());
        assertTrue(e.getMessage().contains("expected " + sha256(DATA)), e.getMessage());
    }

    @Test
    void resumedPartIsFedBeforeTheRest() throws Exception {
        Path part = tmp.resolve("file.part");
        Files.write(part, java.util.Arrays.copyOf(DATA, 10));
        ChecksumVerifier v = ChecksumVerifier.sha256(sha256(DATA));
        v.feed(part);
        try (OutputStream out = v.wrap(new ByteArrayOutputStream())) {
            out.write(DATA, 10, DATA.length - 10);
        }
        v.verify("file");
    }

    @Test
    void streamIsVerifiedInOnePassWithProgress() throws Exception {
        List<Long> progress = new ArrayList<>();
        ChecksumVerifier.verifyStream(new ByteArrayInputStream(DATA), sha256(DATA), "bundled", progress::add);
        assertEquals(List.of((long) DATA.length), progress);

        assertThrows(IntegrityException.class, () -> ChecksumVerifier.verifyStream(
                new ByteArrayInputStream(DATA), sha256(new byte[] {1}), "bundled", bytes -> { }));
    }

    @Test
    void fileIsVerifiedThroughTheSamePath() throws Exception {
        Path file = tmp.resolve("data.bin");
        Files.write(file, DATA);
        ChecksumVerifier.verifyFile(file, sha256(DATA), bytes -> { });
    }

    @Test
    void expectationMustBeSixtyFourHexCharacters() {
        assertThrows(IllegalArgumentException.class, () -> ChecksumVerifier.sha256("abc"));
        assertThrows(IllegalArgumentException.class, () -> ChecksumVerifier.sha256("g".repeat(64)));
    }
}
