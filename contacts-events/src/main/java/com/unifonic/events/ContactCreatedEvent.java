package com.unifonic.events;

import java.time.Instant;
import java.util.UUID;

/** Version 1 contract. Deliberately excludes contact PII and owner identity. */
public record ContactCreatedEvent(UUID eventId, UUID correlationId, Long contactId, Instant occurredAt) {
    public ContactCreatedEvent {
        if (eventId == null || correlationId == null || contactId == null || contactId <= 0 || occurredAt == null)
            throw new IllegalArgumentException("Invalid ContactCreatedEvent v1");
    }
}
