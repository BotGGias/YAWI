package de.yawi.installer.core.download;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Lets tests outside this package use {@link TestHttpServer}. */
public final class TestHttpServerAccess implements AutoCloseable {

    private final TestHttpServer server;

    public TestHttpServerAccess() throws IOException {
        server = new TestHttpServer();
    }

    /** {@code http://127.0.0.1:<port>} without a trailing slash. */
    public String base() {
        String uri = server.uri("/").toString();
        return uri.substring(0, uri.length() - 1);
    }

    /** What {@code /custom} answers. */
    public void custom(String body) {
        server.custom = body.getBytes(StandardCharsets.UTF_8);
    }

    public void customBytes(byte[] body) {
        server.custom = body;
    }

    @Override
    public void close() {
        server.close();
    }
}
