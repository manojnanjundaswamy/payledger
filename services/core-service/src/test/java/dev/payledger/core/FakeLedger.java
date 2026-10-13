package dev.payledger.core;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A stand-in for ledger-service's read API, shared by the tests that read balances and totals (one
 * server, so their Spring contexts are the same and are cached). Each test registers what it expects
 * under its own space's book, so tests don't see each other's answers.
 */
final class FakeLedger {

    /** Path (with query) -> JSON body. Anything else: 404, like a book the ledger has never seen. */
    static final Map<String, String> RESPONSES = new ConcurrentHashMap<>();
    static final AtomicBoolean DOWN = new AtomicBoolean();
    private static final HttpServer SERVER = start();

    private FakeLedger() {
    }

    static String url() {
        return "http://localhost:" + SERVER.getAddress().getPort();
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                // Decoded, as Spring decodes path variables: the client may send "space%3A..." for "space:...".
                String query = exchange.getRequestURI().getQuery();
                String uri = exchange.getRequestURI().getPath() + (query == null ? "" : "?" + query);
                String body = RESPONSES.get(uri);
                int status = DOWN.get() ? 500 : body == null ? 404 : 200;
                byte[] bytes = (body == null ? "{\"code\":\"book-not-found\"}" : body).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
