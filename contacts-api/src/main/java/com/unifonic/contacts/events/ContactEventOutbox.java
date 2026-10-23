package com.unifonic.contacts.events;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class ContactEventOutbox {
    @Inject EntityManager em;
    @Inject CorrelationFilter correlation;

    @Transactional(Transactional.TxType.MANDATORY)
    public void record(Long contactId) {
        ContactOutbox row = new ContactOutbox();
        row.eventId = UUID.randomUUID();
        row.correlationId = correlation.id();
        row.contactId = contactId;
        row.occurredAt = Instant.now();
        row.traceParent = com.unifonic.events.EventTelemetry.traceParent();
        em.persist(row);
    }
}
