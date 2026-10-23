package com.unifonic.contacts;

import io.quarkus.test.junit.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import com.unifonic.events.EventTelemetry;
import com.unifonic.contacts.security.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ContactsSecurityTest.Profile.class)
@QuarkusTestResource(OidcTestServer.class)
class ObservabilityTest {
    @Inject EventTelemetry telemetry;
    @TestHTTPResource URI base;
    @Test void metricsAndHealthAreAvailableWithoutBearerTokens() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/q/health/live", "/q/health/ready"})
                assertEquals(200, client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            telemetry.result("publish", "kafka", "success");
            assertEquals(401, client.send(HttpRequest.newBuilder(base.resolve("/api/v1/contacts")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            var response = client.send(HttpRequest.newBuilder(base.resolve("/q/metrics")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("http_server_requests_seconds_count"));
            assertTrue(response.body().contains("contacts_events_total"));
        }
    }
}
