package com.unifonic.contacts.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unifonic.events.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {
    OutboxPublisher dispatcher;
    ContactOutbox row;
    List<ContactOutbox> rows;
    @BeforeEach void setup() {
        dispatcher = new OutboxPublisher(); dispatcher.enabled = true; dispatcher.telemetry = new com.unifonic.events.EventTelemetry(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), io.opentelemetry.api.OpenTelemetry.noop());
        dispatcher.em = mock(EntityManager.class);
        dispatcher.publisher = mock(RabbitPublisher.class);
        dispatcher.kafka = mock(KafkaPublisher.class);
        dispatcher.json = new ObjectMapper().findAndRegisterModules();
        when(dispatcher.em.find(OutboxRelayLock.class, 1, LockModeType.PESSIMISTIC_WRITE)).thenReturn(new OutboxRelayLock());
        row = row(1L); rows = new ArrayList<>(List.of(row));
        when(dispatcher.em.createQuery(anyString(), eq(ContactOutbox.class))).thenAnswer(call -> {
            String queryText = call.getArgument(0);
            @SuppressWarnings("unchecked") TypedQuery<ContactOutbox> query = mock(TypedQuery.class);
            when(query.setMaxResults(anyInt())).thenReturn(query);
            when(query.setLockMode(any())).thenReturn(query);
            when(query.getResultList()).thenAnswer(ignored -> rows.stream()
                    .filter(item -> queryText.contains("rabbitPublished") ? !item.rabbitPublished : !item.kafkaPublished).toList());
            return query;
        });
    }
    static ContactOutbox row(long sequence) {
        var row = new ContactOutbox(); row.sequenceNumber = sequence; row.eventId = UUID.randomUUID();
        row.correlationId = UUID.randomUUID(); row.contactId = 42L; row.occurredAt = Instant.now(); return row;
    }
    @Test void removesOnlyAfterBothBrokersConfirmSameEventAndPayload() throws Exception {
        dispatcher.dispatch();
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        var order = inOrder(dispatcher.publisher, dispatcher.kafka, dispatcher.em);
        order.verify(dispatcher.publisher).publish(eq(row.event()), payload.capture());
        order.verify(dispatcher.kafka).publish(row.event(), payload.getValue());
        order.verify(dispatcher.em).remove(row);
        assertTrue(row.rabbitPublished); assertTrue(row.kafkaPublished);
    }
    @Test void kafkaFailureDoesNotRepublishAlreadyConfirmedRabbitEvent() throws Exception {
        doThrow(new IOException()).doNothing().when(dispatcher.kafka).publish(eq(row.event()), anyString());
        dispatcher.dispatch();
        assertTrue(row.rabbitPublished); assertFalse(row.kafkaPublished);
        verify(dispatcher.em, never()).remove(any());
        dispatcher.dispatch();
        verify(dispatcher.publisher, times(1)).publish(eq(row.event()), anyString());
        verify(dispatcher.kafka, times(2)).publish(eq(row.event()), anyString());
        verify(dispatcher.em).remove(row);
    }
    @Test void rabbitFailureDoesNotBlockKafkaOrRepublishItOnRecovery() throws Exception {
        doThrow(new IOException()).doNothing().when(dispatcher.publisher).publish(eq(row.event()), anyString());
        dispatcher.dispatch();
        assertFalse(row.rabbitPublished); assertTrue(row.kafkaPublished);
        dispatcher.dispatch();
        verify(dispatcher.publisher, times(2)).publish(eq(row.event()), anyString());
        verify(dispatcher.kafka, times(1)).publish(eq(row.event()), anyString());
        verify(dispatcher.em).remove(row);
    }
    @Test void laterKafkaEventCannotOvertakeFailedEventButRabbitContinues() throws Exception {
        var later = row(2L); rows.add(later);
        doThrow(new IOException()).when(dispatcher.kafka).publish(eq(row.event()), anyString());
        dispatcher.dispatch();
        verify(dispatcher.kafka, never()).publish(eq(later.event()), anyString());
        verify(dispatcher.publisher).publish(eq(later.event()), anyString());
        assertTrue(later.rabbitPublished); assertFalse(later.kafkaPublished);
    }
    @Test void otherBrokerCanProgressBeyondPendingRowsFromPreviousBatch() throws Exception {
        row.rabbitPublished = true;
        doThrow(new IOException()).when(dispatcher.kafka).publish(eq(row.event()), anyString());
        dispatcher.dispatch();
        var later = row(2L); rows.add(later);
        dispatcher.dispatch();
        verify(dispatcher.publisher).publish(eq(later.event()), anyString());
        verify(dispatcher.publisher, never()).publish(eq(row.event()), anyString());
    }
}
