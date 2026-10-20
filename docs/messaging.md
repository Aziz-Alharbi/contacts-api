# Contact-created events and RabbitMQ

## Development setup

The two Quarkus applications use the RabbitMQ Java AMQP 0-9-1 client through the shared `contacts-events` module. The Reporter has no external database service. Its HTTP count endpoint is independent of event consumption; events currently produce structured logs, not an accumulated counter.

```bash
# From the repository root, using the existing .env for Compose's Keycloak settings:
docker compose up -d --wait rabbitmq
mvn install
# Separate terminals, after configuring the existing MySQL/Keycloak requirements:
mvn -pl contacts-api quarkus:dev
mvn -pl contacts-reporter quarkus:dev
```

The initial `mvn install` installs the parent and shared events artifact for single-module development commands. Normal `mvn verify` builds all modules without requiring an installed artifact.

AMQP is bound to localhost:5672. Management UI: http://localhost:15672 (user `contacts`). Complete Compose startup generates the password in a runtime volume unless `RABBITMQ_PASSWORD` is explicitly overridden; see [container credentials](containers.md#local-credentials). For host-based Maven applications, export that generated password or configure the same explicit local override for broker and applications. Both applications read `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, and `RABBITMQ_VHOST`. Compose reads `.env`, but Maven does not automatically export it. Connections are lazy and retried by the scheduled jobs, so a temporarily unavailable broker does not prevent contact creation.

## Version 1 event contract

Routing key and AMQP type version the contract: `contact.created.v1` and `ContactCreatedEvent.v1`.

```json
{
  "eventId": "4a9d1495-60c7-4417-957f-e2610b01e118",
  "correlationId": "a77b2e92-d09e-4c85-a008-5c15d26cdf53",
  "contactId": 42,
  "occurredAt": "2026-10-20T09:00:00Z"
}
```

`eventId` is generated once and retained across publication retries. `correlationId` comes from a valid UUID `X-Correlation-ID` HTTP header, or is generated for the request and returned in the response header. Invalid supplied IDs return 400. `contactId` is a positive database ID; `occurredAt` is a UTC instant. Names, email addresses, phone numbers, owner identities, credentials and tokens are deliberately excluded. The Reporter validates required fields during JSON deserialization. AMQP `messageId` and `correlationId` mirror the payload identifiers.

## Publication and topology

```text
ContactService.create
  -> transaction: contact + contact_event_outbox
  -> committed rows only -> OutboxPublisher (every second, up to 20 rows)
  -> contacts.events [direct], routing key contact.created.v1
  -> contacts-reporter.contact-created.v1 [durable quorum queue]
  -> ContactCreatedConsumer -> success ACK / final failure NACK
  -> contacts.events.dlx [direct]
  -> contacts-reporter.contact-created.v1.dlq [durable quorum queue]
```

Both applications idempotently declare identical exchanges, queues and bindings before using a channel. A `direct` exchange is sufficient because the Reporter needs one exact event type, without wildcard routing. Additional subscribers can bind their own queues to the same key. See RabbitMQ's [exchange routing documentation](https://www.rabbitmq.com/docs/exchanges).

The outbox is inserted in the same database transaction as the new contact, so rollback creates neither a contact nor an event. Updates/deletes do not create this event. The publisher reads committed rows with a pessimistic write lock, publishes persistent messages with `mandatory=true`, waits up to five seconds for a publisher confirmation, checks returned unroutable messages, and records RabbitMQ delivery. The row is removed only after Kafka delivery also confirms; see [dual-broker publication](kafka.md#publication-through-the-existing-outbox). Failed publication leaves the row for a later scheduled run. These broker transport retries continue until recovery; they are separate from the bounded consumer processing attempts.

A broker confirmation and database commit cannot be atomic: a crash after confirmation but before the broker delivery flag commits can cause duplicate delivery. Delivery is **at least once**, and ordering across publishers or reconnections is not guaranteed. The current handler only logs, so duplicate logs are harmless; future counters or other side effects must persistently deduplicate by `eventId`. The outbox has no foreign key to contacts so a pending creation event survives a later deletion.

## Processing, attempts, and DLQ

The consumer polls every second, processing up to 20 messages per run. A message remains unacknowledged during processing. It gets **three total attempts: the first attempt plus two immediate retries**, without sleeping or requeueing between attempts. Successful handling sends `basicAck(tag, false)`. Three failures send `basicNack(tag, false, false)`; the broker routes the original message to the DLQ, with `x-death` metadata. Invalid JSON and invalid contracts follow the same bounded failure path. No raw payload or exception message is logged.

The source quorum queue uses at-least-once dead-lettering (`x-dead-letter-strategy=at-least-once`, `x-overflow=reject-publish`), so RabbitMQ retains a rejected message until the DLQ confirms it. Both the DLX and DLQ are declared before the source queue. The DLQ has no automatic consumer, expiry, or return binding: failed messages stay available for inspection and deliberate replay after fixing the cause. RabbitMQ [documents dead-letter behavior here](https://www.rabbitmq.com/docs/dlx).

A poison message occupies its consumer only for its three processing calls, then later messages continue in the same batch. A slow handler can therefore delay following messages until those calls finish; this implementation does not impose a handler timeout. Connection loss or process termination before ACK can redeliver the message and restart the three-attempt cycle. Thus three is a bound per uninterrupted delivery, not a persistent lifetime attempt counter. The quorum queue's broker delivery limit can also dead-letter repeatedly redelivered messages. ACK/NACK transport errors are propagated to the poll loop rather than counted as business failures.

Logs identify `eventId`, `correlationId`, `contactId` on successful consumption, `attempt=N/3` on failure, and the DLQ on final rejection. A final rejection log means the NACK was sent; broker/DLQ inspection confirms dead-letter arrival. RabbitMQ distinguishes [publisher confirmations and consumer acknowledgements](https://www.rabbitmq.com/docs/confirms).

## Repeatable tests

Default tests require no external broker:

```bash
mvn clean verify
```

`ApiEndpointsTest` checks that successful HTTP creation commits the outbox with the returned correlation ID, while duplicate and missing-group failures add no event. `OutboxPublisherTest` checks confirmation-before-removal and retention/retry after broker failure. `RabbitPublisherTest` checks persistence, IDs, mandatory routing, confirmation failures and returned messages. `ContactCreatedConsumerTest` checks first-attempt success, third-attempt recovery, exactly three failures, malformed contracts, ACK failure handling, a targeted deliberate failure, and processing of a following good event.

Run the real-broker test against a **separate disposable** Compose service (localhost:5673, no persistent volume):

```bash
docker compose --profile integration up -d --wait rabbitmq-test
mvn -pl contacts-reporter -am test -Drabbitmq.integration=true \
  -Dtest=RabbitFlowTest -Dsurefire.failIfNoSpecifiedTests=false
docker compose --profile integration stop rabbitmq-test
```

`RabbitFlowTest` asserts that the queues start empty; it does not purge messages. It publishes good / deliberately failing / good events with the actual publisher and real broker confirmations, then executes the actual consumer and handler. It asserts one handling call for each good message, exactly three calls for the failed one, an empty source queue, and the original failed event in the DLQ with matching IDs and `x-death.reason=rejected`. It acknowledges the inspected DLQ message after its assertions. Do not run another consumer against this test broker. The default suite explicitly skips this one test unless the integration property is set.

For a manual application demonstration:

1. Create a contact through the authenticated API, optionally providing a UUID `X-Correlation-ID`. Expect 201 and an eventual `published` API log and `consumed ... attempt=1 result=success` Reporter log.
2. To target one event for intentional failure, stop the Reporter first, create another contact, and copy its `eventId` from the publisher log. Start the Reporter with `CONTACTS_FAIL_EVENT_ID=<that UUID>`. The setting targets only that event ID and defaults to disabled.
3. Expect `attempt=1/3`, `2/3`, `3/3`, then `dead-lettered ... attempts=3`. Inspect the DLQ in the development management UI. The original payload and IDs are retained. A later good contact still produces a success log.
4. Remove `CONTACTS_FAIL_EVENT_ID` after the demonstration. Inspect/fix failed events before replaying them; replay is manual and may duplicate effects.
5. For outage verification, stop only the development broker, create a contact, and observe 201 plus a pending `contact_event_outbox` row. Restart RabbitMQ; expect eventual publication and outbox removal once both RabbitMQ and Kafka have confirmed. No event is produced for a failed/rolled-back contact creation.

The automated broker test exercises the real AMQP publisher, consumer, topology and DLQ together. The HTTP/outbox atomicity test uses H2 and local OIDC fixtures, not a live MySQL/Keycloak deployment.

## Verification recorded on October 20, 2026

`mvn clean verify` passed: 131 API cases and 89 Reporter/utility cases (220 passed, zero failures/errors). The one opt-in `RabbitFlowTest` case was skipped in this default run. The existing gated API/Reporter classes retained 100% line coverage (142/142 API lines, 24/24 Reporter lines).

The initial image download stalled. A retry on October 20, 2026 successfully started the isolated RabbitMQ broker on port 5673. `RabbitFlowTest` then passed against the real broker at 11:57 Asia/Riyadh: **1 test, zero failures, zero errors, zero skipped; BUILD SUCCESS**. The test verified confirmed publication of three events, successful consumption of the first, exactly three deliberate processing failures for the second, arrival of the original failed event in the DLQ with matching message/correlation IDs and `x-death.reason=rejected`, and successful consumption of the following good event. The source queue was empty afterward. The isolated test broker was stopped after verification.

If the image is already present locally, `docker compose --profile integration up -d --wait --pull never rabbitmq-test` starts it without another registry download. The real-broker test uses the production publisher, consumer, handler and topology; connection provisioning is supplied by the test and the handler is spied only to assert attempt counts. It does not start both complete applications or a live MySQL/Keycloak deployment. HTTP/H2 tests separately verify the outbox transaction.
