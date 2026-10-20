package com.unifonic.reporter.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import com.unifonic.events.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only uses the isolated, disposable Compose test broker on port 5673. */
@EnabledIfSystemProperty(named = "rabbitmq.integration", matches = "true")
class RabbitFlowTest {
    @Test void confirmedPublicationConsumptionPoisonDlqAndFollowingMessage() throws Exception {
        var factory = new ConnectionFactory();
        factory.setHost("localhost"); factory.setPort(5673);
        factory.setUsername("contacts-test"); factory.setPassword("contacts-test-only");
        try (var connection = factory.newConnection(); var inspection = connection.createChannel()) {
            RabbitTopology.declare(inspection);
            // Require an empty disposable broker; never silently purge user messages.
            assertEquals(0, inspection.messageCount(RabbitTopology.QUEUE), "Use a fresh test broker");
            assertEquals(0, inspection.messageCount(RabbitTopology.DLQ), "Use a fresh test broker");
            var broker = mock(RabbitConnection.class);
            when(broker.openChannel()).thenAnswer(call -> {
                var channel = connection.createChannel(); RabbitTopology.declare(channel); return channel;
            });
            var publisher = new RabbitPublisher(broker);
            var json = new ObjectMapper().findAndRegisterModules();
            var first = event(41L); var poison = event(42L); var last = event(43L);
            for (var event : List.of(first, poison, last)) publisher.publish(event, json.writeValueAsString(event));
            assertEquals(3, inspection.messageCount(RabbitTopology.QUEUE));
            var handler = spy(new ContactCreatedHandler());
            handler.failEventId = Optional.of(poison.eventId());
            var consumer = new ContactCreatedConsumer();
            consumer.enabled = true; consumer.broker = broker; consumer.json = json; consumer.handler = handler;
            consumer.poll();
            verify(handler).handle(first, 1);
            for (int i = 1; i <= 3; i++) verify(handler).handle(poison, i);
            verify(handler).handle(last, 1);
            verify(handler, times(5)).handle(any(), anyInt());
            assertEquals(0, inspection.messageCount(RabbitTopology.QUEUE));
            GetResponse dead = null;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (dead == null && System.nanoTime() < deadline) {
                dead = inspection.basicGet(RabbitTopology.DLQ, false);
                if (dead == null) Thread.sleep(50);
            }
            assertNotNull(dead, "Poison message must arrive in the DLQ");
            assertEquals(poison, json.readValue(dead.getBody(), ContactCreatedEvent.class));
            assertEquals(poison.eventId().toString(), dead.getProps().getMessageId());
            assertEquals(poison.correlationId().toString(), dead.getProps().getCorrelationId());
            var deaths = (List<?>) dead.getProps().getHeaders().get("x-death");
            var death = (Map<?, ?>) deaths.getFirst();
            assertEquals("rejected", death.get("reason").toString());
            assertEquals(RabbitTopology.QUEUE, death.get("queue").toString());
            inspection.basicAck(dead.getEnvelope().getDeliveryTag(), false);
            assertNull(inspection.basicGet(RabbitTopology.DLQ, true));
        }
    }
    static ContactCreatedEvent event(long contactId) {
        return new ContactCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), contactId, Instant.now());
    }
}
