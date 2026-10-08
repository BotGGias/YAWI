package de.yawi.installer.core.integrity;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * The packager's tool for creates a signing key pair, signs a
 * manifest, verifies a signature. Run through Maven:
 * <pre>
 * ./mvnw -q compile exec:java@sign-manifest -Dyawi.sign.args="keygen tools/keys/mine"
 * ./mvnw -q compile exec:java@sign-manifest -Dyawi.sign.args="sign dist/installer.xml tools/keys/mine/manifest-signing.key.pem"
 * ./mvnw -q compile exec:java@sign-manifest -Dyawi.sign.args="verify dist/installer.xml dist/installer.xml.sig tools/keys/mine/manifest-signing.pub.pem"
 * </pre>
 * The same files work with OpenSSL:
 * {@code openssl dgst -sha256 -sign key.pem installer.xml | openssl base64 -A > installer.xml.sig}.
 */
public final class ManifestSigner {

    public static final String PRIVATE_KEY_FILE = "manifest-signing.key.pem";
    public static final String PUBLIC_KEY_FILE = "manifest-signing.pub.pem";
    static final int KEY_BITS = 3072;

    private ManifestSigner() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** @return the exit status: 0 ok, 1 verification failed or error, 2 usage */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            return usage(err);
        }
        try {
            switch (args[0]) {
                case "keygen" -> {
                    if (args.length != 2) {
                        return usage(err);
                    }
                    Path dir = Path.of(args[1]);
                    keygen(dir);
                    out.println("Key pair written to " + dir.toAbsolutePath() + " (" + PRIVATE_KEY_FILE + ", "
                            + PUBLIC_KEY_FILE + "); keep the private key out of the repository.");
                    return 0;
                }
                case "sign" -> {
                    if (args.length < 3 || args.length > 4) {
                        return usage(err);
                    }
                    Path manifest = Path.of(args[1]);
                    Path signature = args.length == 4 ? Path.of(args[3]) : signatureFile(manifest);
                    sign(manifest, Path.of(args[2]), signature);
                    out.println("Signed " + manifest + " -> " + signature);
                    return 0;
                }
                case "verify" -> {
                    if (args.length != 4) {
                        return usage(err);
                    }
                    boolean ok = verify(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
                    out.println(ok ? "Signature valid" : "Signature INVALID");
                    return ok ? 0 : 1;
                }
                default -> {
                    return usage(err);
                }
            }
        } catch (IOException | GeneralSecurityException | IllegalStateException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static int usage(PrintStream err) {
        err.println("usage: keygen <dir>");
        err.println("       sign <manifest> <private-key.pem> [<out.sig>]");
        err.println("       verify <manifest> <signature.sig> <public-key.pem>");
        return 2;
    }

    public static Path signatureFile(Path manifest) {
        return manifest.resolveSibling(manifest.getFileName() + ManifestSignatureVerifier.SIGNATURE_SUFFIX);
    }

    /** Writes a fresh RSA pair as PKCS#8 and X.509 PEM into {@code dir}. */
    public static KeyPair keygen(Path dir) throws IOException, GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ManifestSignatureVerifier.KEY_ALGORITHM);
        generator.initialize(KEY_BITS);
        KeyPair pair = generator.generateKeyPair();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(PRIVATE_KEY_FILE),
                ManifestSignatureVerifier.toPem("PRIVATE KEY", pair.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
        Files.writeString(dir.resolve(PUBLIC_KEY_FILE),
                ManifestSignatureVerifier.toPem("PUBLIC KEY", pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);
        return pair;
    }

    public static void sign(Path manifest, Path privateKeyPem, Path signature) throws IOException, GeneralSecurityException {
        PrivateKey key;
        try (InputStream in = Files.newInputStream(privateKeyPem)) {
            key = ManifestSignatureVerifier.readPrivateKey(in);
        }
        Files.write(signature, ManifestSignatureVerifier.sign(Files.readAllBytes(manifest), key));
    }

    public static boolean verify(Path manifest, Path signature, Path publicKeyPem) throws IOException, GeneralSecurityException {
        PublicKey key;
        try (InputStream in = Files.newInputStream(publicKeyPem)) {
            key = ManifestSignatureVerifier.readPublicKey(in);
        }
        return new ManifestSignatureVerifier(key).verify(Files.readAllBytes(manifest), Files.readAllBytes(signature));
    }

    /** For tests and the build: a pair that lives only in memory. */
    public static KeyPair generate() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ManifestSignatureVerifier.KEY_ALGORITHM);
        generator.initialize(KEY_BITS);
        return generator.generateKeyPair();
    }
}
