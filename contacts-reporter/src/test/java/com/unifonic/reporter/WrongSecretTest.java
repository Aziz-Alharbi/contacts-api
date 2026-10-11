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
@TestProfile(WrongSecretTest.Profile.class)
@QuarkusTestResource(value = ReporterUpstream.class, restrictToAnnotatedClass = true)
class WrongSecretTest {
    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.oidc-client.credentials.secret", "incorrect-fixture-secret",
                    "quarkus.oidc-client.client-id", "contacts-reporter");
        }
    }
    @TestHTTPResource("/reports/contacts/count") URI endpoint;
    @Test void providerRejectsWrongSecretAndReporterReturnsGenericFailure() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode(), response.body());
            assertTrue(ReporterUpstream.tokenRequests.get() > 0);
            assertFalse(response.body().contains("incorrect-fixture-secret"));
            assertFalse(response.body().contains("invalid_client"));
        }
    }
}
