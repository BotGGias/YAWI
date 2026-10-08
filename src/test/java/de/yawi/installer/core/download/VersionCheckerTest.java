package de.yawi.installer.core.download;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class VersionCheckerTest {

    private static TestHttpServer server;

    @BeforeAll
    static void start() throws IOException {
        server = new TestHttpServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static VersionChecker checker() {
        return new VersionChecker(HttpClient.newHttpClient(), Duration.ofMillis(500));
    }

    @Test
    void parsesPlainTextAndJson() {
        assertEquals(Optional.of("0.2.0"), VersionChecker.parse(" 0.2.0\n"));
        assertEquals(Optional.of("1.4.0-beta"), VersionChecker.parse("{\"name\":\"x\", \"version\" : \"1.4.0-beta\"}"));
        assertEquals(Optional.empty(), VersionChecker.parse("{\"latest\":\"1\"}"));
        assertEquals(Optional.empty(), VersionChecker.parse("<html>nope</html>"));
        assertEquals(Optional.empty(), VersionChecker.parse(""));
    }

    @Test
    void answersFromAServerAndFailsQuietly() throws Exception {
        server.custom = "{\"version\":\"9.9.9\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(Optional.of("9.9.9"), checker().check(server.uri("/custom")).get());
        assertEquals(Optional.empty(), checker().check(server.uri("/missing")).get(), "404");
        assertEquals(Optional.empty(), checker().check(server.uri("/stall")).get(), "timeout");
        assertEquals(Optional.empty(), checker().check(URI.create("http://127.0.0.1:1/x")).get(), "connection refused");
    }

    @Test
    void versionsCompareNumericallyPerSegment() {
        assertTrue(Version.isNewer("0.10.0", "0.9.5"));
        assertFalse(Version.isNewer("0.9.5", "0.10.0"));
        assertEquals(0, Version.compare("1.2", "1.2.0"));
        assertTrue(Version.isNewer("1.0", "1.0-beta"), "release beats pre-release");
        assertTrue(Version.isNewer("2.0.0", "1.99.99"));
        assertFalse(Version.isNewer("0.1.0", "0.1.0"));
    }
}
