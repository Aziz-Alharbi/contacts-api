package com.unifonic.contacts.events;

import com.unifonic.events.ContactCreatedEvent;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contact_event_outbox")
public class ContactOutbox {
    @Id @Column(name = "eventId") public UUID eventId;
    @Column(name = "correlationId", nullable = false) public UUID correlationId;
    @Column(name = "contactId", nullable = false) public Long contactId;
    @Column(name = "occurredAt", nullable = false) public Instant occurredAt;

    public ContactCreatedEvent event() {
        return new ContactCreatedEvent(eventId, correlationId, contactId, occurredAt);
    }
}
