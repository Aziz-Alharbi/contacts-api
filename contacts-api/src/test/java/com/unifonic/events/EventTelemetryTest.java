package com.unifonic.events;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EventTelemetryTest {
    @Test void durableParentAndMessageScopesKeepTraceAndRestoreMdc() {
        try (var provider = SdkTracerProvider.builder().build()) {
            var registry = new SimpleMeterRegistry();
            var otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var telemetry = new EventTelemetry(registry, otel);
            var event = new ContactCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), 42L, Instant.now());
            Span http = otel.getTracer("test").spanBuilder("HTTP create").startSpan();
            String saved;
            try (var scope = http.makeCurrent()) { saved = EventTelemetry.traceParent(); }
            http.end(); // Outbox dispatch can happen on another thread after the request ended.
            MDC.put("correlationId", "previous");
            try {
                try (var publish = telemetry.open(saved, event, "publish", SpanKind.PRODUCER)) {
                    String sent = EventTelemetry.traceParent();
                    assertEquals(http.getSpanContext().getTraceId(), Span.current().getSpanContext().getTraceId());
                    assertEquals(event.eventId().toString(), MDC.get("eventId"));
                    try (var consume = telemetry.open(sent, event, "consume", SpanKind.CONSUMER)) {
                        assertEquals(http.getSpanContext().getTraceId(), Span.current().getSpanContext().getTraceId());
                        assertEquals(event.correlationId().toString(), MDC.get("correlationId"));
                    }
                }
                assertEquals("previous", MDC.get("correlationId"));
                assertNull(MDC.get("eventId"));
                assertNull(EventTelemetry.traceParent());
            } finally { MDC.remove("correlationId"); }
        }
    }

    @Test void metricDimensionsNeverIncludeEventIdentifiers() {
        { var registry = new SimpleMeterRegistry();
            var telemetry = new EventTelemetry(registry, OpenTelemetry.noop());
            telemetry.result("publish", "rabbitmq", "success");
            telemetry.result("publish", "rabbitmq", "failure");
            assertEquals(1, registry.get("contacts.events").tags("operation", "publish", "broker", "rabbitmq", "result", "failure").counter().count());
            registry.getMeters().forEach(m -> assertEquals(Set.of("operation", "broker", "result"),
                    new HashSet<>(m.getId().getTags().stream().map(t -> t.getKey()).toList())));
        }
    }
    @Test void publishersCarryOnlyTheCurrentW3cParentAlongsideExistingIdentifiers() throws Exception {
        try (var provider = SdkTracerProvider.builder().build()) {
            var telemetry = new EventTelemetry(new SimpleMeterRegistry(), OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            var event = new ContactCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), 42L, Instant.now());
            var connection = org.mockito.Mockito.mock(RabbitConnection.class);
            var channel = org.mockito.Mockito.mock(com.rabbitmq.client.Channel.class);
            org.mockito.Mockito.when(connection.openChannel()).thenReturn(channel);
            var settings = org.mockito.Mockito.mock(KafkaSettings.class);
            org.mockito.Mockito.when(settings.topic()).thenReturn("events");
            var kafka = new KafkaPublisher(settings);
            @SuppressWarnings("unchecked") var producer = (org.apache.kafka.clients.producer.Producer<String,String>) org.mockito.Mockito.mock(org.apache.kafka.clients.producer.Producer.class);
            kafka.producer = producer;
            org.mockito.Mockito.when(producer.send(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
            String parent;
            try (var scope = telemetry.open(null, event, "publish", SpanKind.PRODUCER)) {
                parent = EventTelemetry.traceParent();
                new RabbitPublisher(connection).publish(event, "{}");
                kafka.publish(event, "{}");
            }
            var properties = org.mockito.ArgumentCaptor.forClass(com.rabbitmq.client.AMQP.BasicProperties.class);
            org.mockito.Mockito.verify(channel).basicPublish(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(true), properties.capture(), org.mockito.ArgumentMatchers.any(byte[].class));
            assertEquals(parent, properties.getValue().getHeaders().get("traceparent"));
            @SuppressWarnings("rawtypes") var record = org.mockito.ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
            org.mockito.Mockito.verify(producer).send(record.capture());
            assertEquals(parent, new String(record.getValue().headers().lastHeader("traceparent").value(), java.nio.charset.StandardCharsets.US_ASCII));
        }
    }

}
