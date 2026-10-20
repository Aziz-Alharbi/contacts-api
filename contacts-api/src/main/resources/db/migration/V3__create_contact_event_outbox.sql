CREATE TABLE contact_event_outbox (
    eventId BINARY(16) NOT NULL PRIMARY KEY,
    correlationId BINARY(16) NOT NULL,
    contactId BIGINT NOT NULL,
    occurredAt TIMESTAMP(6) NOT NULL,
    INDEX idx_contact_outbox_time (occurredAt)
);
