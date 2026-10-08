package de.yawi.installer.core.integrity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E10-S02-T01: RSA-SHA256 over the exact manifest bytes, Base64 in the .sig file, PEM keys. */
class ManifestSignatureVerifierTest {

    private static final byte[] MANIFEST = "<installer schemaVersion=\"1\"/>\n".getBytes(StandardCharsets.UTF_8);

    private static KeyPair keys;

    @TempDir
    Path tmp;

    @BeforeAll
    static void keys() throws Exception {
        keys = ManifestSigner.generate();
    }

    private static ManifestSignatureVerifier verifier() {
        return new ManifestSignatureVerifier(keys.getPublic());
    }

    @Test
    void signatureRoundTrips() {
        byte[] sig = ManifestSignatureVerifier.sign(MANIFEST, keys.getPrivate());
        assertTrue(new String(sig, StandardCharsets.US_ASCII).endsWith("\n"), "one line, newline-terminated");
        assertTrue(verifier().verify(MANIFEST, sig));
        // Whitespace around the Base64 is tolerated (editors, line wrapping).
        byte[] wrapped = ("  " + new String(sig, StandardCharsets.US_ASCII).trim().replaceAll("(.{40})", "$1\r\n") + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertTrue(verifier().verify(MANIFEST, wrapped));
    }

    @Test
    void anyChangeToTheManifestBreaksTheSignature() {
        byte[] sig = ManifestSignatureVerifier.sign(MANIFEST, keys.getPrivate());
        byte[] changed = MANIFEST.clone();
        changed[5] ^= 1;
        assertFalse(verifier().verify(changed, sig));
        assertFalse(verifier().verify(Arrays.copyOf(MANIFEST, MANIFEST.length - 1), sig), "trailing newline matters");
    }

    @Test
    void wrongKeyGarbageAndMissingKeyAllFail() throws Exception {
        byte[] sig = ManifestSignatureVerifier.sign(MANIFEST, keys.getPrivate());
        assertFalse(new ManifestSignatureVerifier(ManifestSigner.generate().getPublic()).verify(MANIFEST, sig));
        assertFalse(verifier().verify(MANIFEST, "not base64!".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(verifier().verify(MANIFEST, "QUJD".getBytes(StandardCharsets.US_ASCII)), "valid Base64, wrong length");
        assertFalse(verifier().verify(MANIFEST, new byte[0]));
        assertFalse(new ManifestSignatureVerifier(null).verify(MANIFEST, sig));
        assertFalse(new ManifestSignatureVerifier(null).hasKey());
    }

    @Test
    void pemKeysAreReadBackIdentically() throws Exception {
        String pub = ManifestSignatureVerifier.toPem("PUBLIC KEY", keys.getPublic().getEncoded());
        String priv = ManifestSignatureVerifier.toPem("PRIVATE KEY", keys.getPrivate().getEncoded());
        assertTrue(pub.startsWith("-----BEGIN PUBLIC KEY-----\n"), pub);
        assertTrue(pub.endsWith("-----END PUBLIC KEY-----\n"), pub);
        assertTrue(pub.lines().skip(1).allMatch(l -> l.length() <= 64), "64-column body like OpenSSL");

        PublicKey p = ManifestSignatureVerifier.readPublicKey(stream(pub));
        PrivateKey k = ManifestSignatureVerifier.readPrivateKey(stream(priv));
        assertArrayEquals(keys.getPublic().getEncoded(), p.getEncoded());
        assertArrayEquals(keys.getPrivate().getEncoded(), k.getEncoded());
        assertTrue(new ManifestSignatureVerifier(p).verify(MANIFEST, ManifestSignatureVerifier.sign(MANIFEST, k)));

        assertThrows(IOException.class, () -> ManifestSignatureVerifier.readPublicKey(stream("")));
        assertThrows(IOException.class, () -> ManifestSignatureVerifier.readPublicKey(stream("-----BEGIN PUBLIC KEY-----\n@@@\n-----END PUBLIC KEY-----\n")));
    }

    @Test
    void embeddedKeyLoads() {
        assertTrue(ManifestSignatureVerifier.embedded().hasKey());
    }

    @Test
    void signerToolRoundTrip() throws Exception {
        Path dir = tmp.resolve("keys");
        assertEquals(0, ManifestSigner.run(new String[] {"keygen", dir.toString()}, System.out, System.err));
        assertTrue(Files.isRegularFile(dir.resolve(ManifestSigner.PRIVATE_KEY_FILE)));
        Path manifest = tmp.resolve("installer.xml");
        Files.write(manifest, MANIFEST);

        assertEquals(0, ManifestSigner.run(new String[] {"sign", manifest.toString(),
                dir.resolve(ManifestSigner.PRIVATE_KEY_FILE).toString()}, System.out, System.err));
        Path sig = tmp.resolve("installer.xml.sig");
        assertTrue(Files.isRegularFile(sig), "default name: <manifest>.sig");
        assertEquals(0, ManifestSigner.run(new String[] {"verify", manifest.toString(), sig.toString(),
                dir.resolve(ManifestSigner.PUBLIC_KEY_FILE).toString()}, System.out, System.err));

        Files.write(manifest, "<installer schemaVersion=\"1\"><!-- edited --></installer>".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, ManifestSigner.run(new String[] {"verify", manifest.toString(), sig.toString(),
                dir.resolve(ManifestSigner.PUBLIC_KEY_FILE).toString()}, System.out, System.err));
        assertEquals(2, ManifestSigner.run(new String[] {"frobnicate"}, System.out, System.err));
        assertEquals(2, ManifestSigner.run(new String[0], System.out, System.err));
        assertEquals(1, ManifestSigner.run(new String[] {"sign", "nope.xml", "nokey.pem"}, System.out, System.err));
    }

    @Test
    void policyParsesStrictByDefault() {
        assertEquals(SignaturePolicy.STRICT, SignaturePolicy.parse(null));
        assertEquals(SignaturePolicy.STRICT, SignaturePolicy.parse(""));
        assertEquals(SignaturePolicy.STRICT, SignaturePolicy.parse("${yawi.signature.policy}"), "unfiltered placeholder");
        assertEquals(SignaturePolicy.STRICT, SignaturePolicy.parse("whatever"));
        assertEquals(SignaturePolicy.LENIENT, SignaturePolicy.parse(" Lenient "));
        assertEquals(SignaturePolicy.STRICT, SignaturePolicy.embedded(), "the test build is strict");
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
    }
}
