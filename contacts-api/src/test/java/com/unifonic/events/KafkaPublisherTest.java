package com.unifonic.events;

import org.apache.kafka.clients.producer.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaPublisherTest {
    @Test void usesContactKeyAndWaitsForBrokerConfirmation() throws Exception {
        var settings = mock(KafkaSettings.class);
        var publisher = new KafkaPublisher(settings);
        when(settings.topic()).thenReturn("events");
        @SuppressWarnings("unchecked") Producer<String,String> producer = mock(Producer.class);
        publisher.producer = producer;
        var confirmed = new CompletableFuture<RecordMetadata>();
        confirmed.complete(null);
        when(producer.send(any())).thenReturn(confirmed);
        var event = new ContactCreatedEvent(UUID.randomUUID(),UUID.randomUUID(),42L,Instant.now());
        publisher.publish(event,"{}");
        @SuppressWarnings("unchecked") var record = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(record.capture());
        assertEquals("42",record.getValue().key()); assertEquals("events",record.getValue().topic());
        assertEquals(event.eventId().toString(),new String(record.getValue().headers().lastHeader("eventId").value(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(event.correlationId().toString(),new String(record.getValue().headers().lastHeader("correlationId").value(), java.nio.charset.StandardCharsets.UTF_8));
        confirmed = new CompletableFuture<>(); confirmed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(producer.send(any())).thenReturn(confirmed);
        assertThrows(ExecutionException.class, () -> publisher.publish(event,"{}"));
    }
    @Test void producerRequiresIdempotenceAllAcksAndOrderedInflightRequests() {
        var settings = mock(KafkaSettings.class);
        when(settings.clientProperties()).thenReturn(new Properties());
        var config=KafkaPublisher.producerProperties(settings);
        assertEquals(true,config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
        assertEquals("all",config.get(ProducerConfig.ACKS_CONFIG));
        assertEquals(1,config.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION));
    }
}
