package com.unifonic.reporter;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ReporterTest.Profile.class)
@QuarkusTestResource(value = ReporterUpstream.class, restrictToAnnotatedClass = true)
class ReporterTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.oidc-client.credentials.secret", "fixture-secret",
                    "quarkus.oidc-client.client-id", "contacts-reporter");
        }
    }
    @TestHTTPResource("/reports/contacts/count") URI endpoint;
    HttpResponse<String> report() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void derivesCountAndAttachesServiceTokenWithoutUserLogin() throws Exception {
        ReporterUpstream.apiStatus.set(200);
        ReporterUpstream.page = "{\"items\":[{\"id\":1}],\"page\":0,\"size\":1,\"total\":7}";
        var response = report();
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"contactCount\":7"));
        assertTrue(ReporterUpstream.tokenRequests.get() > 0);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertFalse(response.body().contains("fixture-token"));
    }
    @Test void emptyContactsProduceZero() throws Exception {
        ReporterUpstream.apiStatus.set(200);
        ReporterUpstream.page = "{\"items\":[],\"page\":0,\"size\":1,\"total\":0}";
        var response = report();
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"contactCount\":0"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={401,403,500})
    void upstreamAuthenticationAuthorizationAndFailuresAreSanitized(int status) throws Exception {
        ReporterUpstream.apiStatus.set(status);
        var response = report();
        assertEquals(502, response.statusCode(), response.body());
        assertFalse(response.body().contains("private-upstream-error"));
        assertFalse(response.body().contains("fixture-secret"));
        assertFalse(response.body().contains("fixture-token"));
        assertFalse(response.body().contains("contactCount"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"{}", "{\"total\":-1}",
        "{\"items\":[],\"page\":0,\"size\":1,\"total\":5}",
        "{\"items\":[],\"page\":0,\"size\":1,\"total\":\"0\"}", "invalid-json"})
    void rejectsMalformedAndInconsistentPages(String body) throws Exception {
        ReporterUpstream.apiStatus.set(200);
        ReporterUpstream.page=body;
        var response=report();
        assertTrue(response.statusCode()==502 || response.statusCode()==503,response.body());
        assertFalse(response.body().contains("fixture-token"));
        assertFalse(response.body().contains("contactCount"));
    }
}
