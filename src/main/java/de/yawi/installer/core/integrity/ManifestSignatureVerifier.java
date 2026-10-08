package de.yawi.installer.core.integrity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks a manifest against its detached signature.
 *
 * <p>The scheme is RSA with SHA-256 ({@code SHA256withRSA}, PKCS#1 v1.5) -
 * what {@code openssl dgst -sha256 -sign} produces. The public key is baked
 * into the installer as a PEM resource; the signature file holds the raw
 * signature as Base64 text. Anything that does not decode counts as an
 * invalid signature, never as "no signature".
 */
public final class ManifestSignatureVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(ManifestSignatureVerifier.class);

    public static final String ALGORITHM = "SHA256withRSA";
    public static final String KEY_ALGORITHM = "RSA";
    /** The public key shipped inside the installer; replaced by the packager for a release. */
    public static final String EMBEDDED_KEY_RESOURCE = "/de/yawi/installer/integrity/manifest-signing.pub.pem";
    /** Suffix of the signature file next to a manifest. */
    public static final String SIGNATURE_SUFFIX = ".sig";

    private static final Pattern PEM_ARMOR = Pattern.compile("-----(BEGIN|END)[^-]*-----");

    private final PublicKey key;

    /** @param key the trusted signing key, or {@code null} when none is embedded - every check then fails */
    public ManifestSignatureVerifier(PublicKey key) {
        this.key = key;
    }

    /** The verifier with the installer's embedded key; without the resource no signature can be trusted. */
    public static ManifestSignatureVerifier embedded() {
        try (InputStream in = ManifestSignatureVerifier.class.getResourceAsStream(EMBEDDED_KEY_RESOURCE)) {
            if (in == null) {
                LOG.warn("No manifest signing key embedded ({}); signed manifests cannot be verified",
                        EMBEDDED_KEY_RESOURCE);
                return new ManifestSignatureVerifier(null);
            }
            return new ManifestSignatureVerifier(readPublicKey(in));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Embedded manifest signing key is unreadable: " + e.getMessage(), e);
        }
    }

    public boolean hasKey() {
        return key != null;
    }

    public Optional<PublicKey> key() {
        return Optional.ofNullable(key);
    }

    /**
     * @param manifest      the exact bytes that were signed
     * @param signatureFile the content of the {@code .sig} file: Base64 of the raw signature
     * @return true only if the signature decodes and matches under the embedded key
     */
    public boolean verify(byte[] manifest, byte[] signatureFile) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(signatureFile, "signatureFile");
        if (key == null) {
            LOG.warn("Cannot verify the manifest signature: no signing key embedded");
            return false;
        }
        byte[] signature;
        try {
            signature = decodeSignature(signatureFile);
        } catch (IllegalArgumentException e) {
            LOG.warn("Manifest signature file is not Base64: {}", e.getMessage());
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(manifest);
            return verifier.verify(signature);
        } catch (SignatureException e) {
            LOG.warn("Manifest signature is malformed: {}", e.getMessage());
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        }
    }

    /** Signs {@code manifest}; the counterpart of {@link #verify}, used by the build tool and tests. */
    public static byte[] sign(byte[] manifest, PrivateKey key) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(key);
            signer.update(manifest);
            return encodeSignature(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign with " + ALGORITHM + ": " + e.getMessage(), e);
        }
    }

    /** The {@code .sig} content: Base64 without line breaks, one trailing newline. */
    static byte[] encodeSignature(byte[] rawSignature) {
        return (Base64.getEncoder().encodeToString(rawSignature) + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] decodeSignature(byte[] signatureFile) {
        String text = new String(signatureFile, StandardCharsets.US_ASCII).replaceAll("\\s+", "");
        return Base64.getDecoder().decode(text);
    }

    // --- PEM ----------------------------------------------------------------

    /** Reads an X.509 SubjectPublicKeyInfo PEM ({@code BEGIN PUBLIC KEY}). */
    public static PublicKey readPublicKey(InputStream pem) throws IOException, GeneralSecurityException {
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(new X509EncodedKeySpec(pemBody(pem)));
    }

    /** Reads an unencrypted PKCS#8 PEM ({@code BEGIN PRIVATE KEY}). */
    public static PrivateKey readPrivateKey(InputStream pem) throws IOException, GeneralSecurityException {
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(pemBody(pem)));
    }

    public static String toPem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }

    private static byte[] pemBody(InputStream pem) throws IOException {
        String text = new String(pem.readAllBytes(), StandardCharsets.US_ASCII);
        String body = PEM_ARMOR.matcher(text).replaceAll("").replaceAll("\\s+", "");
        if (body.isEmpty()) {
            throw new IOException("PEM has no content");
        }
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IOException("PEM is not Base64: " + e.getMessage(), e);
        }
    }
}
