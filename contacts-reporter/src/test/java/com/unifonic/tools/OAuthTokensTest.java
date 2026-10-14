package com.unifonic.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OAuthTokensTest {
    HttpServer server;
    String issuer;
    MutableClock clock;
    List<Long> waits;
    List<Map<String,String>> submissions;
    List<String> outputs;
    Deque<String> responses;
    OAuthTokens oauth;
    static final String TOKEN = "header.eyJzdWIiOiJmaXh0dXJlIiwicm9sZXMiOlsidXNlciJdfQ.signature";
    @BeforeEach void startFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/contacts";
        clock = new MutableClock(); waits = new ArrayList<>(); submissions = new ArrayList<>(); outputs = new ArrayList<>(); responses = new ArrayDeque<>();
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var submitted = new HashMap<String,String>();
            Arrays.stream(body.split("&")).filter(pair -> pair.contains("=")).forEach(pair -> {
                String[] parts = pair.split("=", 2); submitted.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            });
            submissions.add(submitted);
            String result = responses.removeFirst();
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(result.contains("\"error\"") ? 400 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        oauth = new OAuthTokens(issuer, HttpClient.newHttpClient(), clock, seconds -> { waits.add(seconds); clock.now += seconds; });
    }
    @AfterEach void stopFixture() { server.stop(0); }
    void deviceResponse(long expires) { responses.add("{\"device_code\":\"fixture-device\",\"user_code\":\"ABCD\",\"verification_uri\":\"http://localhost/verify\",\"expires_in\":" + expires + ",\"interval\":1}"); }
    @ParameterizedTest @ValueSource(strings={"demo-user", "demo-admin"})
    void deviceLoginObtainsTokenForRequestedDemoAccount(String account) throws Exception {
        deviceResponse(30); responses.add("{\"access_token\":\"" + TOKEN + "\"}");
        assertEquals(TOKEN, oauth.device(account, outputs::add));
        assertTrue(outputs.get(2).contains(account)); assertFalse(String.join("", outputs).contains(TOKEN));
        assertEquals(OAuthTokens.DEVICE_GRANT, submissions.get(1).get("grant_type")); assertFalse(submissions.getFirst().containsKey("client_secret")); assertFalse(submissions.getFirst().containsKey("password"));
    }
    @Test void deviceLoginSendsMatchingS256PkceVerifier() throws Exception {
        deviceResponse(30); responses.add("{\"access_token\":\"" + TOKEN + "\"}"); oauth.device("demo-user", outputs::add);
        String verifier = submissions.get(1).get("code_verifier");
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))), submissions.getFirst().get("code_challenge"));
        assertEquals("S256", submissions.getFirst().get("code_challenge_method"));
    }
    @Test void pendingAuthorizationContinuesPolling() throws Exception {
        deviceResponse(30); responses.add("{\"error\":\"authorization_pending\"}"); responses.add("{\"access_token\":\"" + TOKEN + "\"}");
        assertEquals(TOKEN, oauth.device("demo-user", outputs::add)); assertEquals(List.of(1L,1L), waits);
    }
    @Test void slowDownAddsFiveSecondsToPollingInterval() throws Exception {
        deviceResponse(30); responses.add("{\"error\":\"slow_down\"}"); responses.add("{\"access_token\":\"" + TOKEN + "\"}");
        assertEquals(TOKEN, oauth.device("demo-user", outputs::add)); assertEquals(List.of(1L,6L), waits);
    }
    @Test void localDeadlineStopsPollingWithoutSleepingInTest() {
        deviceResponse(2); responses.add("{\"error\":\"authorization_pending\"}");
        assertTrue(assertThrows(IllegalStateException.class, () -> oauth.device("demo-user", outputs::add)).getMessage().contains("expired")); assertEquals(List.of(1L,1L), waits);
    }
    @ParameterizedTest @ValueSource(strings={"access_denied", "expired_token", "invalid_grant"})
    void terminalTokenFailureStopsDevicePolling(String code) {
        deviceResponse(30); responses.add("{\"error\":\"" + code + "\"}");
        assertTrue(assertThrows(IllegalStateException.class, () -> oauth.device("demo-user", outputs::add)).getMessage().contains(code)); assertEquals(1, waits.size());
    }
    @Test void providerErrorTextCannotExposeCredentialDetails() {
        responses.add("{\"error\":\"fixture-sensitive-detail\"}");
        assertEquals("Device login failed: http_error", assertThrows(IllegalStateException.class, () -> oauth.device("demo-user", outputs::add)).getMessage());
    }
    @Test void malformedDeviceResponseIsRejected() {
        responses.add("{\"device_code\":\"fixture-device\"}");
        assertThrows(java.io.IOException.class, () -> oauth.device("demo-user", outputs::add));
    }
    @Test void reporterReadsProvidedSecretAndUsesClientCredentials() throws Exception {
        responses.add("{\"access_token\":\"" + TOKEN + "\"}");
        assertEquals(TOKEN, oauth.reporter(Map.of("CONTACTS_REPORTER_SECRET", "fixture-secret")));
        assertEquals("client_credentials", submissions.getFirst().get("grant_type")); assertEquals("fixture-secret", submissions.getFirst().get("client_secret"));
    }
    @Test void wrongReporterSecretReturnsGenericInvalidClientError() {
        responses.add("{\"error\":\"invalid_client\",\"error_description\":\"fixture-sensitive-secret\"}");
        assertEquals("Token request failed: invalid_client", assertThrows(IllegalStateException.class, () -> oauth.reporter(Map.of("CONTACTS_REPORTER_SECRET", "fixture-secret"))).getMessage());
    }
    @Test void reporterWithoutConfiguredSecretIsRejectedBeforeRequest() { assertThrows(IllegalArgumentException.class, () -> oauth.reporter(Map.of())); assertEquals(0, submissions.size()); }
    @Test void decodeHandlesUnpaddedJwtPayload() throws Exception { assertEquals("fixture", OAuthTokens.decode(TOKEN).path("sub").asText()); }
    @Test void malformedJwtIsRejectedWithoutEchoingIt() { assertEquals("Invalid JWT payload", assertThrows(java.io.IOException.class, () -> OAuthTokens.decode("fixture-invalid")).getMessage()); }
    @Test void selectedClaimsDoNotPrintArbitrarySensitiveClaims() throws Exception { var node = OAuthTokens.JSON.createObjectNode().put("sub", "fixture").put("private", "fixture-hidden"); assertFalse(OAuthTokens.selectedClaims(node).toString().contains("fixture-hidden")); assertEquals(5, OAuthTokens.selectedClaims(node).size()); }
    ObjectNode claims() throws Exception { return (ObjectNode) OAuthTokens.JSON.readTree("{\"sub\":\"service-id\",\"iss\":\"http://localhost:8081/realms/contacts\",\"aud\":\"contacts-api\",\"exp\":200,\"roles\":[\"user\"],\"azp\":\"contacts-reporter\"}"); }
    @Test void reporterCheckAcceptsStringAudience() throws Exception { assertDoesNotThrow(() -> OAuthTokens.validateReporter(claims(), "http://localhost:8081/realms/contacts", 100)); }
    @Test void reporterCheckAcceptsArrayAudience() throws Exception { var claims = claims(); claims.putArray("aud").add("contacts-api").add("account"); assertDoesNotThrow(() -> OAuthTokens.validateReporter(claims, "http://localhost:8081/realms/contacts", 100)); }
    @ParameterizedTest @CsvSource({"sub,''", "iss,wrong-realm", "aud,wrong-api", "azp,wrong-client"})
    void reporterCheckRejectsInvalidIdentityClaim(String claim, String value) throws Exception { var claims = claims(); claims.put(claim, value); assertThrows(IllegalArgumentException.class, () -> OAuthTokens.validateReporter(claims, "http://localhost:8081/realms/contacts", 100)); }
    @Test void reporterCheckRejectsExpiredToken() throws Exception { var claims = claims(); claims.put("exp", 99); assertThrows(IllegalArgumentException.class, () -> OAuthTokens.validateReporter(claims, "http://localhost:8081/realms/contacts", 100)); }
    @Test void reporterCheckRejectsMissingRole() throws Exception { var claims = claims(); claims.putArray("roles"); assertThrows(IllegalArgumentException.class, () -> OAuthTokens.validateReporter(claims, "http://localhost:8081/realms/contacts", 100)); }
    @Test void reporterCheckRejectsAdministratorRole() throws Exception { var claims = claims(); claims.putArray("roles").add("user").add("admin"); assertThrows(IllegalArgumentException.class, () -> OAuthTokens.validateReporter(claims, "http://localhost:8081/realms/contacts", 100)); }
    @Test void nonLocalIssuerRequiresHttps() { assertThrows(IllegalArgumentException.class, () -> new OAuthTokens("http://example.test/realms/contacts")); }
    static class MutableClock extends Clock {
        long now = 100;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(now); }
    }
}
