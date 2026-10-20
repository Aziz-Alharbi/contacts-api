package com.unifonic.reporter.kafka;

import java.util.UUID;

/** Kafka projection: unique creation events observed for this contact. */
public record ContactEventCount(UUID eventId, UUID correlationId, Long contactId, long count) {}
