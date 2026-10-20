ALTER TABLE contact_event_outbox
    DROP PRIMARY KEY,
    ADD COLUMN sequenceNumber BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ADD CONSTRAINT uk_contact_outbox_event UNIQUE (eventId),
    ADD COLUMN rabbitPublished BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN kafkaPublished BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE contact_event_relay_lock (id INTEGER NOT NULL PRIMARY KEY);
INSERT INTO contact_event_relay_lock (id) VALUES (1);
