package dev.payledger.core;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for Keycloak's token and admin endpoints, enough for the admin console: it hands out a token for the
 * right client secret, creates users (409 for an email it has seen) and records every admin call it gets.
 */
final class FakeKeycloak {

    static final String SECRET = "test-admin-secret";
    /** "METHOD path body", in order. */
    static final List<String> CALLS = new CopyOnWriteArrayList<>();
    private static final Set<String> EMAILS = ConcurrentHashMap.newKeySet();
    private static final HttpServer SERVER = start();

    private FakeKeycloak() {
    }

    static String url() {
        return "http://localhost:" + SERVER.getAddress().getPort();
    }

    /** Calls made for one Keycloak user id. */
    static List<String> callsFor(String subject) {
        return CALLS.stream().filter(c -> c.contains("/users/" + subject)).toList();
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int status;
                String response = "";
                if (path.equals("/realms/payledger/protocol/openid-connect/token")) {
                    boolean ok = body.contains("client_id=payledger-core-admin") && body.contains("client_secret=" + SECRET);
                    status = ok ? 200 : 401;
                    response = ok ? "{\"access_token\":\"admin-token\",\"expires_in\":300}" : "{\"error\":\"unauthorized_client\"}";
                } else if (!"Bearer admin-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    status = 401;
                } else if (method.equals("POST") && path.equals("/admin/realms/payledger/users")) {
                    String email = body.replaceAll("(?s).*\"email\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                    if (EMAILS.add(email)) {
                        String id = UUID.randomUUID().toString();
                        exchange.getResponseHeaders().add("Location", url() + "/admin/realms/payledger/users/" + id);
                        status = 201;
                        CALLS.add("CREATE " + email + " /users/" + id + " " + body);
                    } else {
                        status = 409;
                    }
                } else {
                    status = 204;
                    CALLS.add(method + " " + path + " " + body);
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
