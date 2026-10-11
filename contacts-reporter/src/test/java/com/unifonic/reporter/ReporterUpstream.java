package com.unifonic.reporter;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ReporterUpstream implements QuarkusTestResourceLifecycleManager {
    static final AtomicInteger apiStatus = new AtomicInteger(200);
    static final AtomicInteger tokenRequests = new AtomicInteger();
    static volatile String page = "{\"items\":[{\"id\":1}],\"page\":0,\"size\":1,\"total\":7}";
    private HttpServer server;
    @Override public Map<String, String> start() {
        try {
            apiStatus.set(200); tokenRequests.set(0);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/token", exchange -> {
                tokenRequests.incrementAndGet();
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                boolean valid = auth != null && auth.equals("Basic " + Base64.getEncoder()
                        .encodeToString("contacts-reporter:fixture-secret".getBytes(StandardCharsets.UTF_8)));
                valid = valid || (form.contains("client_id=contacts-reporter") && form.contains("client_secret=fixture-secret"));
                valid = valid && form.contains("grant_type=client_credentials");
                String body = valid ? "{\"access_token\":\"fixture-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"
                        : "{\"error\":\"invalid_client\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(valid ? 200 : 401, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.createContext("/api/v1/contacts", exchange -> {
                boolean authorized = "Bearer fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
                boolean correctQuery = "page=0&size=1".equals(exchange.getRequestURI().getQuery());
                int status = authorized && correctQuery ? apiStatus.get() : 401;
                byte[] bytes = (status == 200 ? page : "{\"message\":\"private-upstream-error\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            return Map.of("quarkus.oidc-client.auth-server-url", base,
                    "quarkus.oidc-client.discovery-enabled", "false",
                    "quarkus.oidc-client.token-path", "/token",
                    "quarkus.rest-client.contacts-api.url", base);
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    @Override public void stop() { if (server != null) server.stop(0); }
}
