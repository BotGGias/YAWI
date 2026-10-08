package de.yawi.installer.core.download;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local HTTP server for the download tests: one 1 MiB body with range
 * support, plus endpoints that misbehave in the ways a real server does.
 * No network beyond localhost.
 */
final class TestHttpServer implements AutoCloseable {

    static final int BODY_SIZE = 1024 * 1024;
    static final byte[] BODY = new byte[BODY_SIZE];

    static {
        new Random(42).nextBytes(BODY);
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "test-http");
        t.setDaemon(true);
        return t;
    });
    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger flakyRequests = new AtomicInteger();
    final AtomicInteger dropRequests = new AtomicInteger();
    volatile byte[] custom = new byte[0];

    TestHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/ok", ex -> serve(ex, true));
        server.createContext("/noranges", ex -> serve(ex, false));
        server.createContext("/chunked", this::chunked);
        server.createContext("/flaky", ex -> {
            if (flakyRequests.getAndIncrement() == 0) {
                fail(ex, 500);
            } else {
                serve(ex, true);
            }
        });
        server.createContext("/drop", ex -> {
            if (dropRequests.getAndIncrement() == 0) {
                drop(ex);
            } else {
                serve(ex, true);
            }
        });
        server.createContext("/missing", ex -> fail(ex, 404));
        server.createContext("/redirect", ex -> {
            requests.incrementAndGet();
            ex.getResponseHeaders().add("Location", "/ok");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/stall", this::stall);
        server.createContext("/slow", this::slow);
        server.createContext("/custom", this::serveCustom);
        server.start();
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private void serve(HttpExchange ex, boolean ranges) throws IOException {
        requests.incrementAndGet();
        String range = ex.getRequestHeaders().getFirst("Range");
        int from = 0;
        int status = 200;
        if (ranges) {
            ex.getResponseHeaders().add("Accept-Ranges", "bytes");
            if (range != null && range.startsWith("bytes=")) {
                from = Integer.parseInt(range.substring(6).replace("-", "").trim());
                if (from >= BODY_SIZE) {
                    ex.sendResponseHeaders(416, -1);
                    ex.close();
                    return;
                }
                status = 206;
                ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (BODY_SIZE - 1) + "/" + BODY_SIZE);
            }
        }
        ex.sendResponseHeaders(status, BODY_SIZE - from);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(BODY, from, BODY_SIZE - from);
        }
    }

    private void serveCustom(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        byte[] body = custom;
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void chunked(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        ex.sendResponseHeaders(200, 0); // chunked, no Content-Length
        try (OutputStream out = ex.getResponseBody()) {
            out.write(BODY);
        }
    }

    private void fail(HttpExchange ex, int status) throws IOException {
        requests.incrementAndGet();
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    /** Promises the whole body, sends 300 KiB and hangs up. */
    private void drop(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        ex.getResponseHeaders().add("Accept-Ranges", "bytes");
        ex.sendResponseHeaders(200, BODY_SIZE);
        OutputStream out = ex.getResponseBody();
        out.write(BODY, 0, 300 * 1024);
        out.flush();
        ex.close(); // closes the connection short of the declared length
    }

    /** 10 KiB, then silence. */
    private void stall(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        ex.sendResponseHeaders(200, BODY_SIZE);
        OutputStream out = ex.getResponseBody();
        out.write(BODY, 0, 10 * 1024);
        out.flush();
        try {
            Thread.sleep(20_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ex.close();
    }

    /** 8 KiB every 20 ms: slow enough to cancel in the middle. */
    private void slow(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        ex.sendResponseHeaders(200, BODY_SIZE);
        try (OutputStream out = ex.getResponseBody()) {
            for (int pos = 0; pos < BODY_SIZE; pos += 8 * 1024) {
                out.write(BODY, pos, Math.min(8 * 1024, BODY_SIZE - pos));
                out.flush();
                Thread.sleep(20);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException clientGone) {
            // the client cancelled
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
