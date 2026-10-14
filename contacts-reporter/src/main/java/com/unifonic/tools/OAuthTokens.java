package com.unifonic.tools;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

final class OAuthTokens {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private final String issuer;
    private final HttpClient http;
    private final Clock clock;
    private final Wait wait;

    @FunctionalInterface interface Wait { void seconds(long seconds) throws InterruptedException; }

    OAuthTokens(String issuer) {
        this(issuer, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                Clock.systemUTC(), seconds -> Thread.sleep(Duration.ofSeconds(seconds)));
    }

    OAuthTokens(String issuer, HttpClient http, Clock clock, Wait wait) {
        URI uri = URI.create(issuer);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid Keycloak issuer URL");
        }
        if (!"https".equals(uri.getScheme()) && !Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())) {
            throw new IllegalArgumentException("Keycloak requires HTTPS outside localhost");
        }
        this.issuer = issuer.replaceAll("/$", "");
        this.http = http;
        this.clock = clock;
        this.wait = wait;
    }

    JsonNode post(String endpoint, Map<String, String> values) throws IOException, InterruptedException {
        String form = values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create(issuer + endpoint)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode result;
        try { result = JSON.readTree(response.body()); }
        catch (IOException error) { throw new IOException("Keycloak returned an invalid response"); }
        if (result == null || !result.isObject()) throw new IOException("Keycloak returned an invalid response");
        if (response.statusCode() >= 400) {
            // Never expose provider messages, submitted secrets, or response tokens.
            String code = result.path("error").asText("http_error");
            return JSON.createObjectNode().put("error", safeError(code));
        }
        return result;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static String safeError(String code) {
        return Set.of("authorization_pending", "slow_down", "access_denied", "expired_token",
                "invalid_client", "invalid_grant", "invalid_request", "unauthorized_client").contains(code) ? code : "http_error";
    }

    String reporter(Map<String, String> env) throws IOException, InterruptedException {
        String secret = env.get("CONTACTS_REPORTER_SECRET");
        if (secret == null || secret.isBlank() || secret.startsWith("REPLACE_")) {
            throw new IllegalArgumentException("Set CONTACTS_REPORTER_SECRET in .env or your environment");
        }
        return accessToken(post("/protocol/openid-connect/token", Map.of("grant_type", "client_credentials",
                "client_id", "contacts-reporter", "client_secret", secret)));
    }

    String device(String account, Consumer<String> output) throws Exception {
        String verifier = LocalFiles.randomValue();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        JsonNode device = post("/protocol/openid-connect/auth/device", Map.of("client_id", "contacts-api",
                "scope", "openid profile email", "code_challenge_method", "S256", "code_challenge", challenge));
        if (device.has("error")) throw new IllegalStateException("Device login failed: " + safeError(device.path("error").asText()));
        String deviceCode = requiredText(device, "device_code");
        String userCode = requiredText(device, "user_code");
        String verification = requiredText(device, "verification_uri");
        long expires = positiveNumber(device, "expires_in");
        long interval = device.has("interval") ? positiveNumber(device, "interval") : 5;
        Instant deadline = clock.instant().plusSeconds(expires);
        output.accept("Open: " + verification);
        output.accept("Enter code: " + userCode);
        output.accept("Sign in as " + account + " in Keycloak. The CLI never receives your password.");
        while (clock.instant().isBefore(deadline)) {
            if (Duration.between(clock.instant(), deadline).getSeconds() < interval) break;
            wait.seconds(interval);
            if (!clock.instant().isBefore(deadline)) break;
            JsonNode result = post("/protocol/openid-connect/token", Map.of("grant_type", DEVICE_GRANT,
                    "client_id", "contacts-api", "device_code", deviceCode, "code_verifier", verifier));
            String error = result.path("error").asText();
            if (error.equals("slow_down")) interval += 5;
            else if (!error.equals("authorization_pending")) return accessToken(result);
        }
        throw new IllegalStateException("Device login expired; run the command again");
    }

    void checkReporter(Map<String, String> env) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(issuer + "/.well-known/openid-configuration"))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !issuer.equals(JSON.readTree(response.body()).path("issuer").asText())) {
            throw new IllegalStateException("Discovery issuer does not match the configured realm URL");
        }
        validateReporter(decode(reporter(env)), issuer, clock.instant().getEpochSecond());
    }

    static String accessToken(JsonNode result) {
        String token = result.path("access_token").asText();
        if (token.isBlank()) throw new IllegalStateException("Token request failed: " + safeError(result.path("error").asText()));
        return token;
    }

    static JsonNode decode(String token) throws IOException {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw new IllegalArgumentException();
            JsonNode decoded = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (decoded == null || !decoded.isObject()) throw new IllegalArgumentException();
            return decoded;
        } catch (IllegalArgumentException error) { throw new IOException("Invalid JWT payload"); }
    }

    static ObjectNode selectedClaims(JsonNode claims) {
        var selected = JSON.createObjectNode();
        for (String name : List.of("sub", "iss", "aud", "exp", "roles")) selected.set(name, claims.path(name));
        return selected;
    }

    static void validateReporter(JsonNode payload, String issuer, long now) {
        boolean audience = payload.path("aud").isTextual() ? "contacts-api".equals(payload.path("aud").asText())
                : contains(payload.path("aud"), "contacts-api");
        if (payload.path("sub").asText().isBlank() || !issuer.equals(payload.path("iss").asText()) || !audience
                || !payload.path("exp").isNumber() || payload.path("exp").asDouble() <= now
                || !contains(payload.path("roles"), "user") || contains(payload.path("roles"), "admin")
                || !"contacts-reporter".equals(payload.path("azp").asText())) {
            throw new IllegalArgumentException("Reporter claims fail subject, issuer, audience, expiration, roles, or client checks");
        }
    }

    private static boolean contains(JsonNode array, String value) {
        if (!array.isArray()) return false;
        for (JsonNode item : array) if (item.isTextual() && value.equals(item.asText())) return true;
        return false;
    }

    private static String requiredText(JsonNode node, String name) throws IOException {
        if (!node.path(name).isTextual() || node.path(name).asText().isBlank()) throw new IOException("Invalid device authorization response");
        return node.path(name).asText();
    }

    private static long positiveNumber(JsonNode node, String name) throws IOException {
        if (!node.path(name).isIntegralNumber() || !node.path(name).canConvertToLong() || node.path(name).asLong() <= 0
                || node.path(name).asLong() > 86400) throw new IOException("Invalid device authorization timing");
        return node.path(name).asLong();
    }
}
