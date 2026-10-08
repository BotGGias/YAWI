package de.yawi.installer.core.integrity;

import de.yawi.installer.core.error.IntegrityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * SHA-256 of an artifact against the manifest's {@code sha256}.
 *
 * <p>Meant to run <em>while</em> the data is written: {@link #wrap} puts the
 * digest between the downloader and its output stream, so a verified file is
 * never read a second time. The one exception is a resumed download, where
 * the part already on disk goes through {@link #feed} before the rest arrives.
 * Bundled data, which is streamed rather than written, is checked by
 * {@link #verifyStream} in a single pass before anything uses it.
 */
public final class ChecksumVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(ChecksumVerifier.class);

    public static final String ALGORITHM = "SHA-256";
    private static final int HEX_LENGTH = 64;
    private static final int BUFFER = 64 * 1024;

    private final MessageDigest digest;
    private final byte[] expected;
    private final String expectedHex;

    private ChecksumVerifier(String expectedHex) {
        this.expectedHex = expectedHex.toLowerCase(Locale.ROOT);
        this.expected = HexFormat.of().parseHex(this.expectedHex);
        this.digest = newDigest();
    }

    /** @param expectedHex the manifest's {@code sha256}: exactly 64 hex characters */
    public static ChecksumVerifier sha256(String expectedHex) {
        Objects.requireNonNull(expectedHex, "expectedHex");
        if (expectedHex.length() != HEX_LENGTH) {
            throw new IllegalArgumentException("sha256 must be " + HEX_LENGTH + " hex characters, got "
                    + expectedHex.length());
        }
        try {
            return new ChecksumVerifier(expectedHex);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sha256 is not hex: " + expectedHex, e);
        }
    }

    public String expectedHex() {
        return expectedHex;
    }

    /** Everything written to the returned stream is digested on its way to {@code out}. */
    public OutputStream wrap(OutputStream out) {
        return new DigestOutputStream(out, digest);
    }

    public void update(byte[] buffer, int offset, int length) {
        digest.update(buffer, offset, length);
    }

    /**
     * Digests a file that already exists - the part of a resumed download
     * that an earlier attempt wrote. Nothing else should need this.
     */
    public void feed(Path existingPrefix) throws IOException {
        try (InputStream in = Files.newInputStream(existingPrefix)) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
    }

    /**
     * Compares what was digested with the expectation. The verifier is spent
     * afterwards.
     *
     * @param fileName the file as the user knows it, for the message
     * @throws IntegrityException on mismatch
     */
    public void verify(String fileName) {
        byte[] actual = digest.digest();
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IntegrityException(fileName, "sha256 expected " + expectedHex + ", was " + hex(actual));
        }
        LOG.info("Checksum of {} verified ({})", fileName, expectedHex);
    }

    /**
     * Reads {@code in} to its end and verifies it. For data that is not
     * written anywhere first (bundled sources) and for files assembled by
     * someone else (BitTorrent).
     *
     * @param progress called with the bytes read so far, at most about every 64 KiB
     * @throws IntegrityException on mismatch
     */
    public static void verifyStream(InputStream in, String expectedHex, String fileName, LongConsumer progress)
            throws IOException {
        ChecksumVerifier verifier = sha256(expectedHex);
        byte[] buffer = new byte[BUFFER];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            verifier.update(buffer, 0, n);
            total += n;
            progress.accept(total);
        }
        verifier.verify(fileName);
    }

    /** {@link #verifyStream} over a file. */
    public static void verifyFile(Path file, String expectedHex, LongConsumer progress) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            verifyStream(in, expectedHex, file.getFileName().toString(), progress);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is mandatory in every JRE", e);
        }
    }
}
