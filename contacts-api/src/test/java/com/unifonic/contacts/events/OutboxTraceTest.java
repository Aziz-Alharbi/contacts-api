package com.unifonic.contacts.events;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class OutboxTraceTest {
    @Test void savesRequestTraceContextAlongsideTheEventInTheExistingTransaction() {
        var outbox = new ContactEventOutbox();
        outbox.em = mock(EntityManager.class);
        outbox.correlation = mock(CorrelationFilter.class);
        UUID correlation = UUID.randomUUID();
        when(outbox.correlation.id()).thenReturn(correlation);
        try (var provider = SdkTracerProvider.builder().build()) {
            var otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var span = otel.getTracer("test").spanBuilder("HTTP create").startSpan();
            try (var scope = span.makeCurrent()) { outbox.record(42L); }
            finally { span.end(); }
            var row = ArgumentCaptor.forClass(ContactOutbox.class);
            verify(outbox.em).persist(row.capture());
            assertTrue(row.getValue().traceParent.contains(span.getSpanContext().getTraceId()));
            assertEquals(correlation, row.getValue().event().correlationId());
            assertEquals(42L, row.getValue().event().contactId());
        }
    }
}
