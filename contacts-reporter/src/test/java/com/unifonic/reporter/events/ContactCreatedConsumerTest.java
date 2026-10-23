package com.unifonic.reporter.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import com.unifonic.events.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactCreatedConsumerTest {
    io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics;
    ContactCreatedConsumer consumer;
    Channel channel;
    ContactCreatedEvent event;
    GetResponse delivery;

    @BeforeEach void setup() throws Exception {
        metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        consumer = new ContactCreatedConsumer(); consumer.telemetry = new com.unifonic.events.EventTelemetry(metrics, io.opentelemetry.api.OpenTelemetry.noop());
        consumer.json = new ObjectMapper().findAndRegisterModules();
        consumer.handler = mock(ContactCreatedHandler.class);
        channel = mock(Channel.class);
        event = new ContactCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), 42L, Instant.now());
        delivery = delivery(consumer.json.writeValueAsBytes(event));
    }

    static GetResponse delivery(byte[] body) {
        return new GetResponse(new Envelope(7, false, RabbitTopology.EXCHANGE, RabbitTopology.KEY),
                new AMQP.BasicProperties(), body, 0);
    }

    @Test void acknowledgesOnlyAfterSuccessfulHandling() throws Exception {
        consumer.process(channel, delivery);
        var order = inOrder(consumer.handler, channel);
        order.verify(consumer.handler).handle(event, 1);
        order.verify(channel).basicAck(7, false);
        assertEquals(1, metrics.get("contacts.events").tags("operation", "process", "broker", "rabbitmq", "result", "success").counter().count());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test void transientFailureRecoversOnThirdAttempt() throws Exception {
        doThrow(new IllegalStateException()).when(consumer.handler).handle(event, 1);
        doThrow(new IllegalStateException()).when(consumer.handler).handle(event, 2);
        consumer.process(channel, delivery);
        verify(consumer.handler).handle(event, 3);
        verify(channel).basicAck(7, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test void poisonMessageGetsExactlyThreeAttemptsAndDoesNotBlockNextMessage() throws Exception {
        doThrow(new IllegalStateException()).when(consumer.handler).handle(eq(event), anyInt());
        consumer.process(channel, delivery);
        verify(consumer.handler, times(3)).handle(eq(event), anyInt());
        verify(channel).basicNack(7, false, false);
        var good = new ContactCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), 43L, Instant.now());
        consumer.process(channel, delivery(consumer.json.writeValueAsBytes(good)));
        verify(consumer.handler).handle(good, 1);
        verify(channel).basicAck(7, false);
    }

    @Test void malformedOrInvalidContractsGoToDlqWithoutInvokingHandler() throws Exception {
        for (String body : new String[]{"not-json", "{}", "null"}) {
            consumer.process(channel, delivery(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        verifyNoInteractions(consumer.handler);
        verify(channel, times(3)).basicNack(7, false, false);
    }

    @Test void ackFailureIsPropagatedWithoutRetryingBusinessProcessingOrRejecting() throws Exception {
        doThrow(new IOException()).when(channel).basicAck(7, false);
        assertThrows(IOException.class, () -> consumer.process(channel, delivery));
        verify(consumer.handler, times(1)).handle(event, 1);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test void deliberateFailureTargetsOnlyConfiguredEvent() {
        var handler = new ContactCreatedHandler();
        handler.failEventId = java.util.Optional.of(event.eventId());
        assertThrows(IllegalStateException.class, () -> handler.handle(event, 1));
        handler.handle(new ContactCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), 43L, Instant.now()), 1);
    }
}
