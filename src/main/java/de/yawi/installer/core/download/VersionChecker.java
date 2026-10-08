package de.yawi.installer.core.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks a {@code <versionCheck url>} for the version currently available
 * online. Fails quietly: any problem - unreachable, timeout,
 * 404, garbage - is logged and yields an empty answer, never an error the
 * wizard would have to show.
 *
 * <p>Accepted answers: the version as plain text ({@code 0.2.0}) or a JSON
 * object with a {@code "version"} member. No JSON library, a regex is enough.
 */
public final class VersionChecker {

    private static final Logger LOG = LoggerFactory.getLogger(VersionChecker.class);
    private static final Pattern JSON_VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PLAIN_VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.\\-+_]{0,63}");

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;
    private final Duration timeout;

    public VersionChecker() {
        this(HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build(),
                DEFAULT_TIMEOUT);
    }

    public VersionChecker(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    /** The online version, or empty if it could not be determined. Never completes exceptionally. */
    public CompletableFuture<Optional<String>> check(URI url) {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(timeout).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        LOG.info("Version check {} answered HTTP {}", url, response.statusCode());
                        return Optional.<String>empty();
                    }
                    Optional<String> version = parse(response.body());
                    if (version.isEmpty()) {
                        LOG.info("Version check {} returned nothing usable", url);
                    }
                    return version;
                })
                .exceptionally(e -> {
                    LOG.info("Version check {} failed: {}", url, e.getCause() == null ? e.toString() : e.getCause().toString());
                    return Optional.empty();
                });
    }

    /** JSON {@code "version"} member or the trimmed body if it looks like a version. */
    static Optional<String> parse(String body) {
        if (body == null) {
            return Optional.empty();
        }
        String text = body.strip();
        if (text.startsWith("{")) {
            Matcher m = JSON_VERSION.matcher(text);
            return m.find() ? Optional.of(m.group(1).strip()) : Optional.empty();
        }
        return PLAIN_VERSION.matcher(text).matches() ? Optional.of(text) : Optional.empty();
    }
}
