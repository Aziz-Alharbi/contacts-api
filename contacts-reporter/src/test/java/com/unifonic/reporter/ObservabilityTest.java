package com.unifonic.reporter;

import io.quarkus.test.junit.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ReporterTest.Profile.class)
@QuarkusTestResource(value=ReporterUpstream.class, restrictToAnnotatedClass=true)
class ObservabilityTest {
    @jakarta.inject.Inject com.unifonic.events.EventTelemetry telemetry;
    @TestHTTPResource URI base;
    @Test void metricsAndSeparatedHealthEndpointsRespond() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/q/health/live", "/q/health/ready"})
                assertEquals(200, client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            ReporterUpstream.apiStatus.set(200);
            ReporterUpstream.page = "{\"items\":[],\"page\":0,\"size\":1,\"total\":0}";
            assertEquals(200, client.send(HttpRequest.newBuilder(base.resolve("/reports/contacts/count")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            telemetry.result("process", "rabbitmq", "success");
            var response = client.send(HttpRequest.newBuilder(base.resolve("/q/metrics")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("http_server_requests_seconds_count"));
            assertTrue(response.body().contains("contacts_events_total"));
        }
    }
}
