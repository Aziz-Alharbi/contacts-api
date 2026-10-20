# Publish contact events to Kafka with durable deduplication

Contact creation must reach both RabbitMQ and Kafka even when one broker is unavailable. Extend the existing transactional outbox with ordered rows, a relay lock, and independent confirmation flags; retain each event until both brokers confirm it.

Publish the existing versioned contract using `contactId` as the Kafka key. Add a Kafka Streams Reporter projection with persistent event-ID deduplication, changelog recovery, and exactly-once transactions for offsets/state/count results. RabbitMQ consumption and the existing HTTP count endpoint remain available.

Document development configuration, production consistency boundaries, per-contact ordering, retention, recovery, and duplicate handling. Include real-broker tests for HTTP creation/dual publication, duplicate suppression across restart into a new state directory, partition order, and the existing RabbitMQ DLQ path.

Validation: `mvn clean verify -Dkafka.integration=true -Drabbitmq.integration=true` passed all 231 cases, with zero failures/errors/skips and the existing coverage gate passing. Executed the outbox migration on MySQL 8.4 with a pre-existing row and verified preservation, sequence assignment, pending broker flags, and the relay lock. Real Kafka tests verified same-contact partition ordering and duplicate suppression after restarting into a fresh state directory; the real dual-broker and RabbitMQ DLQ tests passed.
