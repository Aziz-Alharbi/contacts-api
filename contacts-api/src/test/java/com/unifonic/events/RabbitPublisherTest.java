package com.unifonic.events;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RabbitPublisherTest {
    Channel channel;
    RabbitPublisher publisher;
    ContactCreatedEvent event;
    @BeforeEach void setup() throws Exception {
        var broker = mock(RabbitConnection.class);
        channel = mock(Channel.class);
        when(broker.openChannel()).thenReturn(channel);
        publisher = new RabbitPublisher(broker);
        event = new ContactCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), 42L, Instant.now());
    }
    @Test void persistentMandatoryPublicationIncludesIdsAndWaitsForConfirmation() throws Exception {
        publisher.publish(event, "{}");
        var props = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        var order = inOrder(channel);
        order.verify(channel).confirmSelect();
        order.verify(channel).addReturnListener(any(ReturnCallback.class));
        order.verify(channel).basicPublish(eq(RabbitTopology.EXCHANGE), eq(RabbitTopology.KEY), eq(true), props.capture(), any(byte[].class));
        order.verify(channel).waitForConfirmsOrDie(5000);
        assertEquals(2, props.getValue().getDeliveryMode());
        assertEquals(event.eventId().toString(), props.getValue().getMessageId());
        assertEquals(event.correlationId().toString(), props.getValue().getCorrelationId());
    }
    @Test void confirmationFailureIsNotReportedAsSuccess() throws Exception {
        doThrow(new IOException("nack")).when(channel).waitForConfirmsOrDie(5000);
        assertThrows(IOException.class, () -> publisher.publish(event, "{}"));
        verify(channel).close();
    }
    @Test void returnedUnroutableMessageIsFailureEvenWhenBrokerConfirms() throws Exception {
        doAnswer(call -> { ((ReturnCallback) call.getArgument(0)).handle(null); return null; })
                .when(channel).addReturnListener(any(ReturnCallback.class));
        assertThrows(IOException.class, () -> publisher.publish(event, "{}"));
    }
}
