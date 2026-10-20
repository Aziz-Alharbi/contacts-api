# Kafka contact events

## Running locally

Kafka runs alongside RabbitMQ; neither replaces the other. The broker uses KRaft without ZooKeeper. The Compose health check calls Kafka's topic API to verify readiness.

```bash
docker compose up -d --wait kafka rabbitmq
mvn install
# Separate terminals, with the existing MySQL and Keycloak settings configured:
mvn -pl contacts-api quarkus:dev
mvn -pl contacts-reporter quarkus:dev
```

Applications on the host connect to `localhost:9092`; the broker's internal listener is `kafka:19092`. Ports are bound to localhost. Kafka data is stored in the `kafka-data` volume. The environment file is read by Compose, but Maven requires customized values to be exported in its shell.

| Environment variable | Default | Purpose |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka endpoints for both applications |
| `KAFKA_CONTACT_TOPIC` | `contacts.contact-created.v1` | Source event topic |
| `KAFKA_COUNTS_TOPIC` | `contacts.reporter.contact-counts.v1` | Compacted result topic |
| `KAFKA_PARTITIONS` | `3` | Fixed partition count for both topics |
| `KAFKA_REPLICATION_FACTOR` | `1` | Development replication factor, including Streams changelogs |
| `KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | Client protocol; use authenticated TLS for production |
| `KAFKA_SASL_MECHANISM` | `PLAIN` | SASL mechanism when using SASL |
| `KAFKA_SASL_JAAS_CONFIG` | unset | SASL credentials when required; keep secret |
| `KAFKA_REPORTER_APPLICATION_ID` | `contacts-reporter-v1` | Stable Streams application/group and changelog namespace |
| `KAFKA_STATE_DIR` | `.kafka-state` | Disposable local Streams cache; excluded from Git |

Both applications provision and validate the versioned topics using the Kafka Admin client before publishing/consuming. Automatic broker topic creation is disabled. In production, provision topics and ACLs centrally and give the application the required permissions. The source topic retains its history (`retention.ms=-1`); the result topic is compacted. Internal changelog topics are managed by Streams.

## Contract and ordering

Kafka reuses the existing `ContactCreatedEvent` record in `contacts-events`, with AMQP type `ContactCreatedEvent.v1`. It contains only `eventId`, `correlationId`, positive `contactId`, and UTC `occurredAt`; no contact PII or owner identity is added. The same outbox row is serialized for both brokers, preserving all four fields. Kafka headers also carry the event ID, correlation ID and type.

The Kafka message key is the decimal `contactId`. The [Kafka partitioning model](https://kafka.apache.org/41/design/design/#the-producer) keeps records for the same key in one partition while the partition count and partitioner remain unchanged. Ordering means **Kafka append order within a partition**, not sorting by `occurredAt`, and there is no global ordering across contacts/partitions. The consumer rejects a missing/wrong key rather than counting the event in the wrong partition's state.

The publisher enables idempotence, uses `acks=all`, restricts in-flight requests to one and waits for broker confirmation. Relay batches are serialized across API instances with a database lock row. Each broker reads its own pending events in the database-generated `sequenceNumber` order, and stops its batch at the first failed publication so a later event cannot overtake it on that broker. The other broker still attempts its own pending batch. Events for one source partition are processed synchronously by Streams, including after rebalances.

Do not increase the source topic's partition count in place: the same key could move partitions, affecting both order and the location of its deduplication state. Topic initialization rejects a count different from the configured value. If future event types produce multiple events for an existing contact, serialize the corresponding database writes per contact (for example with a contact-row lock) before assigning outbox sequence numbers. The current application emits one creation event per newly created contact; the ordering test deliberately publishes several distinct IDs for the same contact to exercise Kafka's guarantee.

## Publication through the existing outbox

```text
HTTP create -> one database transaction: Contact + ContactOutbox
  -> committed outbox, ordered relay
     -> RabbitMQ confirms -> rabbitPublished=true
     -> Kafka confirms    -> kafkaPublished=true
  -> delete outbox row only when both flags are true

Kafka source -> Streams transaction:
  source offsets + processed-event changelog + per-contact count changelog + result record
```

Migration `V4__track_broker_publications.sql` adds the sequence and two persistent flags to the existing table. Existing pending rows start with both flags false, so they reach both brokers. Old UUID event IDs remain unique and unchanged. A new singleton lock table serializes relay batches. No additional outbox is introduced.

A successful database commit can be followed by broker failure. The contact remains created and the outbox row remains pending for that broker. Subsequent scheduled runs retry it. If RabbitMQ confirmed and Kafka failed, `rabbitPublished=true` is committed while `kafkaPublished=false`; subsequent attempts send only to Kafka. The inverse works the same way. A broker outage does not stop the other from processing newer batches because their pending queries are independent.

Database transactions and broker confirmations are not a distributed atomic transaction. If the process crashes after a broker accepted a message but before the flag commits, the next relay can publish the same event again. Producer idempotence handles transport retries within a producer session; it does not remove duplicate business events resent after a restart. The consumer's durable event-ID check handles that case. A transaction rollback removes both the contact and its uncommitted outbox event. Updates/deletes still do not emit creation events.

For production: retain the same event IDs on retries; monitor pending row age/count and relay errors; operate with redundant brokers, replication factor at least three and appropriate minimum in-sync replicas; preserve Kafka topics/changelogs and stable application IDs; use authenticated TLS, least-privilege ACLs and backup/recovery procedures. For higher throughput, use a CDC-based relay or carefully partitioned workers with per-contact ordering and the same delivery tracking. Never delete an event merely because one broker accepted it, or treat an asynchronous send invocation as confirmation. RabbitMQ and Kafka publication remain eventually consistent with each other.

## Durable deduplication without a Reporter database service

Reporter uses Kafka Streams' persistent state stores and Kafka-backed changelogs. There is **no new SQL database or external database service**. The local RocksDB files are an embedded state cache; Kafka changelogs provide recovery if that entire directory is lost. This is deliberately more than an in-memory `Set` or a local JSON file.

For each partition, the processor validates the event and checks its immutable `eventId` in `contact-event-ids`. If already present it logs `result=ignored` and produces no result record. Otherwise it records the ID, increments that contact's unique-event count in `contact-event-counts`, and emits `ContactEventCount` to the compacted result topic, keyed by `contactId`. That result contains the latest unique event's IDs, contact ID and count. An event ID must always refer to the same immutable contact event/key.

Streams runs with `processing.guarantee=exactly_once_v2`. As described in the [Apache Streams configuration documentation](https://kafka.apache.org/41/streams/developer-guide/config-streams/), source offsets, state changelog records and Kafka output records commit atomically. If processing fails before commit, they are replayed together. Reading results with `isolation.level=read_committed` hides aborted output records. Restarting with the same application ID restores its partition state and offsets; an explicitly duplicated event is ignored even when submitted again after that restart. Instances of one logical Reporter must share this application ID; changing it creates a separate projection.

The event-based count is the number of **unique creation events observed for a contact**, not the live count of contacts. The existing `/reports/contacts/count` endpoint still calls Contacts API and reflects ownership/deletions; it is unchanged. RabbitMQ continues to log its messages and does not increment the Kafka projection, so consuming the same event on both transports does not count it twice.

Application log statements are outside Kafka transactions and can appear again during recovery. Exactly-once refers to the committed Kafka count/state/result, not console output or arbitrary future external side effects. Such side effects require their own idempotency protocol. The deduplication store has no expiry: deleting changelog records or changing retention/application IDs can invalidate historical deduplication. Size and retention policies need operational planning; the development source history also grows until managed explicitly.

Invalid payloads, wrong keys and processing errors fail closed: the stream client stops and does not silently commit past the offending record. Inspect/remediate the source record and restart after fixing the cause. This Kafka path does not reuse the RabbitMQ retry/DLQ policy; RabbitMQ still has its existing three-attempt/DLQ behavior. Broker startup failures are retried by the scheduled startup job, while a processing-error shutdown remains stopped for operator investigation.

## Tests

Default unit and HTTP tests need no running brokers:

```bash
mvn clean verify
```

They check broker-specific outbox flags, identical payloads, partial publication failure/recovery, no resend to an already-confirmed broker, no overtaking on failure, Kafka producer settings/headers/key, rejection of invalid payloads/keys, duplicate suppression, and per-contact result order.

Run all checks, including the **real isolated brokers**, with:

```bash
docker compose --profile integration up -d --wait kafka-test rabbitmq-test
mvn clean verify -Dkafka.integration=true -Drabbitmq.integration=true
docker compose --profile integration stop kafka-test rabbitmq-test
```

Kafka test clients connect to localhost:9094; RabbitMQ tests use localhost:5673. These services use disposable storage and are independent of development topics/queues. If images are already cached, add `--pull never` to the Compose startup command.

For a focused integration run:

```bash
mvn test -Dkafka.integration=true -Drabbitmq.integration=true \
  '-Dtest=KafkaFlowTest,DualBrokerFlowTest,RabbitFlowTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

`DualBrokerFlowTest` creates a contact through the authenticated HTTP API with H2 and an OIDC fixture, reads its committed outbox event, runs the actual relay, and compares the events received from both real brokers. It verifies removal only after both confirmations. `KafkaFlowTest` uses the real publisher, Streams topology and broker: it publishes a duplicate and several contact events, verifies monotonically increasing offsets in a single source partition, checks ordered unique results/counts, closes the consumer, starts it with an entirely new local directory but the same application ID, republishes the old event, and verifies that only the following new event changes the count. A `read_committed` consumer inspects the actual Kafka results. `RabbitFlowTest` retains the real RabbitMQ success/failure/DLQ checks.

The integration test topics use random names and are left on the disposable test broker until it is recreated. Tests do not purge development messages. The dual-broker test consumes/acknowledges its inspected RabbitMQ message; run these tests without an unrelated consumer attached to the test queues.

## Manual inspection

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:19092 --describe --topic contacts.contact-created.v1
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:19092 --topic contacts.reporter.contact-counts.v1 \
  --from-beginning --consumer-property isolation.level=read_committed \
  --property print.key=true
```

Create an authenticated contact through the existing API. API logs identify `broker=rabbitmq` and `broker=kafka` with the same event/correlation/contact IDs. Reporter logs identify the Kafka event and count. To demonstrate broker outage recovery, stop only one development broker, create a contact, inspect the retained row's flags, restart that broker, and verify eventual row removal. Kafka results are observable in the compacted result topic; the automated integration test provides the reproducible duplicate/restart demonstration without editing application code.

## Verified results

On October 20, 2026, `mvn clean verify -Dkafka.integration=true -Drabbitmq.integration=true` completed with **BUILD SUCCESS**: 137 API cases and 94 Reporter/utility cases, **231 tests total, zero failures, zero errors, zero skipped**. The existing business/endpoint coverage gate passed with 100% of its included lines covered (142/142 API and 24/24 Reporter). This gate retains its existing scope; the Kafka topology and transport are additionally exercised by their specific tests.

The real Kafka test verified duplicate suppression both before and after a consumer restart into a fresh local state directory, ordered same-contact offsets/results in one partition, and count progression 1 -> 2 -> 3 rather than counting duplicate submissions. The dual-broker test verified authenticated contact creation and identical event delivery to RabbitMQ and Kafka. The real RabbitMQ test verified the unchanged three-attempt/DLQ path and consumption of the following valid message.

The migration was also executed against an isolated MySQL 8.4 container with an existing outbox row: all prior schema migrations and the new migration succeeded; the old contact/event was preserved, a positive sequence was assigned, both delivery flags were false, and the relay lock row existed. The temporary MySQL container and isolated brokers were stopped after verification.

The development Kafka service was separately started with its named persistent volume and reached Compose `Healthy`; it was then stopped, preserving that volume.
