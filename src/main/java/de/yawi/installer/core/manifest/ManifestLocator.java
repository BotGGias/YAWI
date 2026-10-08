package de.yawi.installer.core.manifest;

import de.yawi.installer.core.error.IntegrityException;
import de.yawi.installer.core.integrity.ManifestSignatureVerifier;
import de.yawi.installer.core.integrity.SignaturePolicy;
import de.yawi.installer.core.manifest.ManifestOrigin.Kind;
import de.yawi.installer.core.manifest.ManifestOrigin.Signature;
import de.yawi.installer.core.xml.SecureXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Finds and loads the manifest. Lookup order:
 * <ol>
 *   <li>the explicit {@code --manifest=<file|url>} argument - binding, no fallback</li>
 *   <li>{@code installer.xml} next to the application</li>
 *   <li>{@code /installer.xml} on the classpath, i.e. bundled in the jar</li>
 * </ol>
 *
 * <p>Next to every manifest a {@code .sig} may lie. One that is
 * present must verify under the embedded key, whatever the origin. One that
 * is missing is fine for a local manifest; a manifest from the network
 * without it is rejected unless the build's {@link SignaturePolicy} is
 * lenient - and even then its command steps stay refused.
 */
public final class ManifestLocator {

    public static final String MANIFEST_FILE_NAME = "installer.xml";
    public static final String CLASSPATH_RESOURCE = "/" + MANIFEST_FILE_NAME;

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final Logger LOG = LoggerFactory.getLogger(ManifestLocator.class);

    private final Path appDir;
    private final Function<String, URL> classpath;
    private final HttpClient http;
    private final ManifestSignatureVerifier verifier;
    private final SignaturePolicy policy;
    private final ManifestParser parser = new ManifestParser();

    /**
     * @param appDir    directory of the running application, or {@code null}
     *                  to skip that lookup (development from a classes directory)
     * @param classpath resolves an absolute resource name such as {@code /installer.xml}
     *                  (and {@code /installer.xml.sig}; {@code null} = absent)
     * @param http      client for {@code --manifest=https://…}
     * @param verifier  checks {@code .sig} files
     * @param policy    what to do with a network manifest that has none
     */
    public ManifestLocator(Path appDir, Function<String, URL> classpath, HttpClient http,
                           ManifestSignatureVerifier verifier, SignaturePolicy policy) {
        this.appDir = appDir;
        this.classpath = Objects.requireNonNull(classpath, "classpath");
        this.http = Objects.requireNonNull(http, "http");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** With the embedded key and a strict policy. */
    public ManifestLocator(Path appDir, Function<String, URL> classpath, HttpClient http) {
        this(appDir, classpath, http, ManifestSignatureVerifier.embedded(), SignaturePolicy.STRICT);
    }

    /** Locator for the running application: embedded key, the build's policy. */
    public static ManifestLocator forRuntime() {
        return new ManifestLocator(applicationDirectory(), ManifestLocator.class::getResource,
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                ManifestSignatureVerifier.embedded(), SignaturePolicy.embedded());
    }

    /**
     * @param explicit value of {@code --manifest=}, or {@code null}
     * @throws ManifestException if nothing is found or the manifest is rejected
     */
    public InstallManifest locate(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return loadExplicit(explicit.trim());
        }

        List<String> searched = new ArrayList<>();
        if (appDir != null) {
            Path candidate = appDir.resolve(MANIFEST_FILE_NAME);
            searched.add(candidate.toString());
            if (Files.isRegularFile(candidate)) {
                return loadFile(candidate, Kind.APP_DIR);
            }
        } else {
            LOG.debug("No application directory known, skipping the {} lookup next to the app", MANIFEST_FILE_NAME);
        }

        searched.add("classpath:" + CLASSPATH_RESOURCE);
        URL bundled = classpath.apply(CLASSPATH_RESOURCE);
        if (bundled != null) {
            try {
                byte[] bytes = readLimited(bundled.openStream(), CLASSPATH_RESOURCE);
                URL sig = classpath.apply(CLASSPATH_RESOURCE + ManifestSignatureVerifier.SIGNATURE_SUFFIX);
                Optional<byte[]> signature = sig == null ? Optional.empty() : Optional.of(sig.openStream().readAllBytes());
                return load(bytes, signature, Kind.CLASSPATH, CLASSPATH_RESOURCE);
            } catch (IOException e) {
                throw new ManifestException("Bundled manifest " + CLASSPATH_RESOURCE + " could not be read: "
                        + e.getMessage(), List.of(), e);
            }
        }

        throw new ManifestException("No manifest found. Searched: " + String.join(", ", searched)
                + ". Pass one with --manifest=<file|url>.", List.of());
    }

    private InstallManifest loadExplicit(String explicit) {
        String lower = explicit.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return loadUrl(explicit);
        }
        Path file = Path.of(explicit);
        if (!Files.isRegularFile(file)) {
            throw new ManifestException("Manifest not found: " + file.toAbsolutePath()
                    + " (given as --manifest=" + explicit + ")", List.of());
        }
        return loadFile(file, Kind.EXPLICIT_FILE);
    }

    private InstallManifest loadFile(Path file, Kind kind) {
        String location = file.toAbsolutePath().toString();
        try {
            byte[] bytes = readLimited(Files.newInputStream(file), location);
            Path sig = file.resolveSibling(file.getFileName() + ManifestSignatureVerifier.SIGNATURE_SUFFIX);
            Optional<byte[]> signature = Files.isRegularFile(sig) ? Optional.of(Files.readAllBytes(sig)) : Optional.empty();
            return load(bytes, signature, kind, location);
        } catch (IOException e) {
            throw new ManifestException("Manifest " + file + " could not be read: " + e.getMessage(), List.of(), e);
        }
    }

    private InstallManifest loadUrl(String url) {
        byte[] bytes = get(url, url).orElseThrow(() -> new ManifestException(
                "Manifest could not be downloaded from " + url + ": HTTP 404", List.of()));
        // The signature lies next to the manifest; a 404 there means "unsigned", anything else is an error.
        Optional<byte[]> signature = get(url + ManifestSignatureVerifier.SIGNATURE_SUFFIX, url);
        return load(bytes, signature, Kind.EXPLICIT_URL, url);
    }

    /**
     * GET with the size limit applied while reading.
     *
     * @param url      what to fetch
     * @param manifest the manifest URL, for messages
     * @return the body, or empty on 404
     */
    private Optional<byte[]> get(String url, String manifest) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(new URI(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/xml, text/xml, */*")
                    .GET()
                    .build();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new ManifestException("Manifest URL is invalid: " + manifest, List.of(), e);
        }

        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ManifestException("Manifest could not be downloaded from " + url + ": " + e.getMessage(),
                    List.of(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ManifestException("Manifest download from " + url + " was interrupted", List.of(), e);
        }

        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new ManifestException("Manifest could not be downloaded from " + url
                        + ": HTTP " + response.statusCode(), List.of());
            }
            return Optional.of(readLimited(body, url));
        } catch (IOException e) {
            throw new ManifestException("Manifest could not be downloaded from " + url + ": " + e.getMessage(),
                    List.of(), e);
        }
    }

    /**
     * The signature decision (E10-S02-T02), then the parse.
     *
     * @throws IntegrityException if a signature is present but invalid, or
     *         missing where the policy demands one
     */
    private InstallManifest load(byte[] bytes, Optional<byte[]> signature, Kind kind, String location) {
        boolean remote = kind == Kind.EXPLICIT_URL;
        Signature status;
        if (signature.isPresent()) {
            if (!verifier.verify(bytes, signature.get())) {
                LOG.error("Manifest {} has a signature that does not verify - refusing it", location);
                throw new IntegrityException(location, "manifest signature invalid"
                        + (verifier.hasKey() ? "" : " (no signing key embedded in this build)"));
            }
            LOG.info("Manifest {} signature verified", location);
            status = Signature.VERIFIED;
        } else if (remote && policy == SignaturePolicy.STRICT) {
            LOG.error("Manifest {} comes from the network and is not signed - refusing it", location);
            throw new IntegrityException(location, "manifest from the network is not signed (no "
                    + MANIFEST_FILE_NAME + ManifestSignatureVerifier.SIGNATURE_SUFFIX + " next to it)");
        } else {
            if (remote) {
                LOG.warn("Manifest {} comes from the network and is not signed; accepted because the signature "
                        + "policy is lenient. Its command steps will not run.", location);
            }
            status = Signature.UNSIGNED;
        }
        return loaded(parser.parse(bytes, new ManifestOrigin(kind, location, status)));
    }

    private static byte[] readLimited(InputStream in, String location) {
        try (InputStream stream = in) {
            return SecureXml.readLimited(stream, ManifestParser.MAX_MANIFEST_BYTES);
        } catch (SecureXml.SizeLimitExceededException e) {
            throw new ManifestException("Manifest " + location + " is larger than the allowed "
                    + ManifestParser.MAX_MANIFEST_BYTES + " bytes", List.of(), e);
        } catch (IOException e) {
            throw new ManifestException("Manifest " + location + " could not be read: " + e.getMessage(),
                    List.of(), e);
        }
    }

    private static InstallManifest loaded(InstallManifest manifest) {
        LOG.info("Manifest loaded from {} ({}, {}): product {} {}", manifest.origin().kind(),
                manifest.origin().location(), manifest.origin().signature(), manifest.product().id(),
                manifest.product().version());
        return manifest;
    }

    /**
     * Directory the application runs from: the jpackage app directory (E16),
     * else the directory of the jar. {@code null} when running from a classes
     * directory, i.e. in development.
     */
    static Path applicationDirectory() {
        String jpackage = System.getProperty("jpackage.app-path");
        if (jpackage != null) {
            return Path.of(jpackage).toAbsolutePath().getParent();
        }
        try {
            CodeSource source = ManifestLocator.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path location = Path.of(source.getLocation().toURI());
            if (Files.isRegularFile(location)) {
                return location.toAbsolutePath().getParent();
            }
            return null;
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            LOG.debug("Cannot determine the application directory", e);
            return null;
        }
    }
}
