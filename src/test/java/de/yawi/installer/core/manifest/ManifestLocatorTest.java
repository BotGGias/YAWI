package de.yawi.installer.core.manifest;

import com.sun.net.httpserver.HttpServer;
import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.IntegrityException;
import de.yawi.installer.core.integrity.ManifestSignatureVerifier;
import de.yawi.installer.core.integrity.ManifestSigner;
import de.yawi.installer.core.integrity.SignaturePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestLocatorTest {

    private static final Function<String, URL> NO_CLASSPATH = name -> null;
    /** {@code /installer.xml} is the full example; nothing else (no {@code .sig}) exists on this "classpath". */
    private static final Function<String, URL> TEST_CLASSPATH = name ->
            ManifestLocator.CLASSPATH_RESOURCE.equals(name) ? ManifestLocatorTest.class.getResource("/manifest/full.xml") : null;

    private static KeyPair keys;
    private static ManifestSignatureVerifier verifier;

    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer server;

    @BeforeAll
    static void keys() throws GeneralSecurityException {
        keys = ManifestSigner.generate();
        verifier = new ManifestSignatureVerifier(keys.getPublic());
    }

    private ManifestLocator locator(SignaturePolicy policy) {
        return new ManifestLocator(appDir, TEST_CLASSPATH, http, verifier, policy);
    }

    private static byte[] signed(byte[] manifest) {
        return ManifestSignatureVerifier.sign(manifest, keys.getPrivate());
    }

    @TempDir
    Path appDir;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static byte[] full() {
        return TestManifests.bytes("/manifest/full.xml");
    }

    @Test
    void explicitFileWins() throws IOException {
        Path explicit = appDir.resolve("other.xml");
        Files.write(explicit, full());
        Files.write(appDir.resolve("installer.xml"), "<garbage/>".getBytes());

        InstallManifest m = new ManifestLocator(appDir, TEST_CLASSPATH, http).locate(explicit.toString());

        assertEquals(ManifestOrigin.Kind.EXPLICIT_FILE, m.origin().kind());
        assertEquals(explicit.toAbsolutePath().toString(), m.origin().location());
    }

    @Test
    void explicitFileMustExist() {
        ManifestException e = assertThrows(ManifestException.class,
                () -> new ManifestLocator(appDir, TEST_CLASSPATH, http).locate(appDir.resolve("missing.xml").toString()));
        assertTrue(e.getMessage().contains("Manifest not found"), e.getMessage());
        assertTrue(e.getMessage().contains("missing.xml"), e.getMessage());
    }

    @Test
    void appDirBeatsClasspath() throws IOException {
        Files.write(appDir.resolve("installer.xml"), full());

        InstallManifest m = new ManifestLocator(appDir, TEST_CLASSPATH, http).locate(null);

        assertEquals(ManifestOrigin.Kind.APP_DIR, m.origin().kind());
        assertTrue(m.origin().location().endsWith("installer.xml"));
    }

    @Test
    void fallsBackToClasspath() {
        InstallManifest m = new ManifestLocator(appDir, TEST_CLASSPATH, http).locate("  ");

        assertEquals(ManifestOrigin.Kind.CLASSPATH, m.origin().kind());
        assertEquals("/installer.xml", m.origin().location());
    }

    @Test
    void noAppDirSkipsThatLookup() {
        InstallManifest m = new ManifestLocator(null, TEST_CLASSPATH, http).locate(null);
        assertEquals(ManifestOrigin.Kind.CLASSPATH, m.origin().kind());
    }

    @Test
    void nothingFoundNamesEveryLocation() {
        ManifestException e = assertThrows(ManifestException.class,
                () -> new ManifestLocator(appDir, NO_CLASSPATH, http).locate(null));

        assertTrue(e.getMessage().startsWith("No manifest found"), e.getMessage());
        assertTrue(e.getMessage().contains(appDir.resolve("installer.xml").toString()), e.getMessage());
        assertTrue(e.getMessage().contains("classpath:/installer.xml"), e.getMessage());
        assertTrue(e.getMessage().contains("--manifest="), e.getMessage());
    }

    @Test
    void invalidManifestFromAppDirIsRejectedWithProblems() throws IOException {
        Files.write(appDir.resolve("installer.xml"), TestManifests.bytes("/manifest/invalid/dependson-self.xml"));

        ManifestException e = assertThrows(ManifestException.class,
                () -> new ManifestLocator(appDir, TEST_CLASSPATH, http).locate(null));
        assertEquals(1, e.getErrors().size());
        assertTrue(e.getMessage().contains("core -> core"), e.getMessage());
    }

    // --- http ------------------------------------------------------------

    private String serve(int status, byte[] body) throws IOException {
        return serve(status, body, null);
    }

    /** Serves the manifest at {@code /installer.xml} and, if given, the signature at {@code /installer.xml.sig}. */
    private String serve(int status, byte[] body, byte[] signature) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // One root handler: the JDK server would otherwise match "/installer.xml.sig" to the "/installer.xml" context.
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] response;
            int code;
            if (path.equals("/installer.xml")) {
                code = status;
                response = body;
            } else if (path.equals("/installer.xml.sig") && signature != null) {
                code = 200;
                response = signature;
            } else {
                code = 404;
                response = "not here".getBytes();
            }
            exchange.sendResponseHeaders(code, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/installer.xml";
    }

    @Test
    void loadsFromHttpWhenLenient() throws IOException {
        String url = serve(200, full());

        InstallManifest m = locator(SignaturePolicy.LENIENT).locate(url);

        assertEquals(ManifestOrigin.Kind.EXPLICIT_URL, m.origin().kind());
        assertEquals(url, m.origin().location());
        assertTrue(m.origin().isRemote());
        assertEquals(ManifestOrigin.Signature.UNSIGNED, m.origin().signature());
        assertFalse(m.origin().isTrusted(), "unsigned from the network: commands must not run");
    }

    // --- E10-S02: signatures ----------------------------------------------

    @Test
    void signedManifestFromHttpIsVerified() throws IOException {
        String url = serve(200, full(), signed(full()));

        InstallManifest m = locator(SignaturePolicy.STRICT).locate(url);

        assertEquals(ManifestOrigin.Signature.VERIFIED, m.origin().signature());
        assertTrue(m.origin().isTrusted());
        assertTrue(m.origin().toString().endsWith(", signed"), m.origin().toString());
    }

    @Test
    void unsignedManifestFromHttpIsRejectedByDefault() throws IOException {
        String url = serve(200, full());

        IntegrityException e = assertThrows(IntegrityException.class,
                () -> locator(SignaturePolicy.STRICT).locate(url));
        assertEquals(ErrorCode.INTEGRITY_FAILED, e.code());
        assertEquals(url, e.fileName());
        assertTrue(e.getMessage().contains("not signed"), e.getMessage());
    }

    @Test
    void invalidSignatureIsRejectedEvenWhenLenient() throws IOException {
        byte[] tampered = new String(full(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("version=\"2.4.1\"", "version=\"9.9.9\"").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(java.util.Arrays.equals(full(), tampered), "the fixture has the version the test edits");
        String url = serve(200, tampered, signed(full()));

        IntegrityException e = assertThrows(IntegrityException.class,
                () -> locator(SignaturePolicy.LENIENT).locate(url));
        assertTrue(e.getMessage().contains("signature invalid"), e.getMessage());

        server.stop(0);
        String garbage = serve(200, full(), "not base64 at all!".getBytes());
        assertThrows(IntegrityException.class, () -> locator(SignaturePolicy.LENIENT).locate(garbage));
    }

    @Test
    void localManifestWithSignatureIsVerifiedAndWithoutIsFine() throws IOException {
        Path file = appDir.resolve("signed.xml");
        Files.write(file, full());
        Files.write(appDir.resolve("signed.xml.sig"), signed(full()));
        assertEquals(ManifestOrigin.Signature.VERIFIED, locator(SignaturePolicy.STRICT).locate(file.toString()).origin().signature());

        Path plain = appDir.resolve("plain.xml");
        Files.write(plain, full());
        InstallManifest m = locator(SignaturePolicy.STRICT).locate(plain.toString());
        assertEquals(ManifestOrigin.Signature.UNSIGNED, m.origin().signature());
        assertTrue(m.origin().isTrusted(), "local files are trusted without a signature");

        Files.write(appDir.resolve("plain.xml.sig"), signed("something else".getBytes()));
        assertThrows(IntegrityException.class, () -> locator(SignaturePolicy.STRICT).locate(plain.toString()),
                "a signature that is there must be right, even for a local file");
    }

    @Test
    void withoutAnEmbeddedKeyNoSignatureVerifies() throws IOException {
        String url = serve(200, full(), signed(full()));
        ManifestLocator noKey = new ManifestLocator(appDir, TEST_CLASSPATH, http,
                new ManifestSignatureVerifier(null), SignaturePolicy.STRICT);
        IntegrityException e = assertThrows(IntegrityException.class, () -> noKey.locate(url));
        assertTrue(e.getMessage().contains("no signing key embedded"), e.getMessage());
    }

    @Test
    void embeddedKeyIsTheDevelopmentKey() throws Exception {
        ManifestSignatureVerifier embedded = ManifestSignatureVerifier.embedded();
        assertTrue(embedded.hasKey(), "the development key ships as a resource");
        java.security.PrivateKey dev;
        try (java.io.InputStream in = Files.newInputStream(Path.of("tools/keys/dev/manifest-signing.key.pem"))) {
            dev = ManifestSignatureVerifier.readPrivateKey(in);
        }
        assertTrue(embedded.verify(full(), ManifestSignatureVerifier.sign(full(), dev)),
                "resource and tools/keys/dev must be the same pair");
    }

    @Test
    void httpErrorStatusIsReported() throws IOException {
        String url = serve(404, "not here".getBytes());

        ManifestException e = assertThrows(ManifestException.class,
                () -> new ManifestLocator(appDir, TEST_CLASSPATH, http).locate(url));
        assertTrue(e.getMessage().contains("HTTP 404"), e.getMessage());
    }

    @Test
    void httpBodyIsSizeLimited() throws IOException {
        String url = serve(200, new byte[(int) ManifestParser.MAX_MANIFEST_BYTES + 1]);

        ManifestException e = assertThrows(ManifestException.class,
                () -> new ManifestLocator(appDir, TEST_CLASSPATH, http).locate(url));
        assertTrue(e.getMessage().contains("larger than"), e.getMessage());
    }

    @Test
    void unreachableHostIsReported() {
        ManifestException e = assertThrows(ManifestException.class,
                () -> new ManifestLocator(appDir, TEST_CLASSPATH, http).locate("http://127.0.0.1:1/installer.xml"));
        assertTrue(e.getMessage().contains("could not be downloaded"), e.getMessage());
    }

    @Test
    void applicationDirectoryIsNullWhenRunningFromClasses() {
        // Under Maven the classes live in target/classes, not in a jar.
        assertEquals(null, ManifestLocator.applicationDirectory());
    }
}
