package com.unifonic.contacts.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unifonic.events.RabbitPublisher;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {
    OutboxPublisher dispatcher;
    ContactOutbox row;
    @BeforeEach void setup() {
        dispatcher = new OutboxPublisher();
        dispatcher.enabled = true;
        dispatcher.em = mock(EntityManager.class);
        dispatcher.publisher = mock(RabbitPublisher.class);
        dispatcher.json = new ObjectMapper().findAndRegisterModules();
        @SuppressWarnings("unchecked") TypedQuery<ContactOutbox> query = mock(TypedQuery.class);
        when(dispatcher.em.createQuery(anyString(), eq(ContactOutbox.class))).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.setLockMode(any())).thenReturn(query);
        row = new ContactOutbox(); row.eventId = UUID.randomUUID(); row.correlationId = UUID.randomUUID();
        row.contactId = 42L; row.occurredAt = Instant.now();
        when(query.getResultList()).thenReturn(List.of(row));
    }
    @Test void removesOutboxRowOnlyAfterConfirmedPublication() throws Exception {
        dispatcher.dispatch();
        var order = inOrder(dispatcher.publisher, dispatcher.em);
        order.verify(dispatcher.publisher).publish(eq(row.event()), anyString());
        order.verify(dispatcher.em).remove(row);
    }
    @Test void brokerFailureKeepsPendingEventAndNextRunRetriesSameId() throws Exception {
        doThrow(new IOException()).doNothing().when(dispatcher.publisher).publish(eq(row.event()), anyString());
        dispatcher.dispatch();
        verify(dispatcher.em, never()).remove(any());
        dispatcher.dispatch();
        verify(dispatcher.publisher, times(2)).publish(eq(row.event()), anyString());
        verify(dispatcher.em).remove(row);
    }
}
