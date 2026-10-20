# Running the complete system with Docker

## Start

From a clean checkout on `feature/a3-containers`, with Docker Desktop or Docker Engine and Docker Compose installed:

```bash
docker compose up --build
```

This builds both Java applications from source and starts Contacts API, Contacts Reporter, MySQL 8.4, Keycloak, RabbitMQ and Kafka. A one-time `local-secrets` helper generates random local credentials. No host Maven/JDK, prebuilt `target` directory, `.env`, SQL initialization command or identity utility invocation is required. The first build downloads Maven dependencies and base images, so it requires network access and takes longer than subsequent builds. Allow at least 4 GB of Docker memory; 6 GB gives more room while building and running the full system.

For detached startup that waits for readiness:

```bash
docker compose up --build -d --wait
docker compose ps
```

| Service | Local address | Internal address |
|---|---|---|
| Contacts API | http://localhost:8080 | `http://contacts-api:8080` |
| Contacts Reporter | http://localhost:8082 | `http://contacts-reporter:8082` |
| Keycloak | http://localhost:8081 | `http://keycloak:8080` |
| MySQL | `docker compose exec mysql ...` (internal only) | `mysql:3306` |
| RabbitMQ AMQP / UI | localhost:5672 / http://localhost:15672 | `rabbitmq:5672` |
| Kafka | localhost:9092 | `kafka:19092` |

Published ports bind only to `127.0.0.1`. Containers connect through service names and container ports, not the host's localhost. Kafka advertises separate internal and external listeners. Optional `.env` overrides for published ports are listed in `.env.example`.

## Images and runtime users

Each application has its own multi-stage Dockerfile and final image. The builder uses Maven and Java 21 and builds its module with the shared `contacts-events` module. The runtime contains a Java 21 JRE and Quarkus fast-JAR output in separate dependency/application layers. It does not contain the Maven installation, source checkout, tests, or build-stage Maven cache.

```bash
docker build -f contacts-api/src/main/docker/Dockerfile.jvm -t contacts-api .
docker build -f contacts-reporter/src/main/docker/Dockerfile.jvm -t contacts-reporter .
```

Use the **repository root** as build context. The root `.dockerignore` permits only reviewed POMs, sources, and container scripts/settings. Application builders copy main sources only; the separate test Dockerfile can also use test sources. It excludes `.git`, `.env` files, JWTs, private keys, exports, host build outputs and the local Kafka cache. No credentials are build arguments or Dockerfile environment values. Container Maven settings contain only public repository URLs, use an explicit repository order and disable remote snapshots; the shared dependency cache is locked while each builder uses it.

Both runtime images declare `USER 10001:10001`. Compose drops Linux capabilities, enables `no-new-privileges` and uses an init process for both applications. Reporter stores its recoverable Kafka Streams cache in `reporter-state`; it still has no external database service. JDBC uses a dedicated `contacts` MySQL user rather than root.

## Local credentials

The `local-secrets` helper uses Java `SecureRandom` to create seven distinct 256-bit URL-safe credentials in `development-secrets`. Existing files are retained across restarts; malformed or symlinked existing credential files are rejected instead of silently replaced. Only that helper mounts the volume writable. Dependent services mount it read-only and load required credentials at startup. These files are readable by the different service UIDs within containers that mount the volume, so the volume is a local-development convenience, not a production secret manager.

Generated values are never written to the source checkout, image layers, build output or helper logs. They remain in Docker's local volume storage. Use a production secret manager, TLS and production service configurations for deployment beyond this local environment.

To look up the generated development credentials deliberately, run the appropriate command locally:

```bash
docker compose exec keycloak cat /run/development-secrets/demo-user
docker compose exec keycloak cat /run/development-secrets/demo-admin
docker compose exec keycloak cat /run/development-secrets/keycloak-admin
docker compose exec rabbitmq cat /run/development-secrets/rabbitmq
```

Users are `demo-user`, `demo-admin`, Keycloak bootstrap `bootstrap-admin`, and RabbitMQ `contacts`. If you explicitly set a credential override in `.env`, that override takes precedence over the generated file; use the override when signing in. Keep `.env` outside Git. An existing realm, RabbitMQ database or MySQL volume retains its original credentials: changing an override is not an automatic credential-rotation mechanism.

## Identity and existing API behavior

Keycloak imports the existing `contacts` realm, roles and clients. Password grants remain disabled. Human users use authorization code with PKCE or device authorization; Reporter uses Client Credentials. JWT audience, signature, subject and role enforcement, contact ownership, and administrator-only deletion remain in place.

The browser-facing issuer remains `http://localhost:8081/realms/contacts`, controlled by `KEYCLOAK_PUBLIC_URL`. API and Reporter use internal `keycloak:8080` endpoints. API explicitly validates that public issuer and fetches JWKS internally; Reporter uses an internal token endpoint. Keycloak enables a dynamic backchannel. If changing the published Keycloak port, also change `KEYCLOAK_PUBLIC_URL` to match it; changing an issuer intentionally invalidates tokens from the previous issuer.

Flyway creates/migrates the database automatically, including the existing dual-broker outbox and relay lock. Contact creation still commits contact and outbox together, tracks RabbitMQ and Kafka confirmations independently, and retries pending publications. RabbitMQ's bounded retries/DLQ and Kafka's ordering/durable deduplication remain enabled. See [messaging](messaging.md) and [Kafka consistency and recovery](kafka.md).

## Readiness and startup order

| Service | Ready when |
|---|---|
| Secret helper | Generation or validation of all credential files succeeds |
| MySQL | Authenticated TCP `SELECT 1` succeeds, after actual server initialization |
| Keycloak | Management `/health/ready` returns HTTP 200 |
| RabbitMQ | Node is running, protocol ports respond, and there are no local alarms |
| Kafka | Its topic API responds over the internal broker listener |
| Contacts API | `/q/health/ready` succeeds, including datasource and RabbitMQ/Kafka checks |
| Contacts Reporter | `/q/health/ready` succeeds, including brokers and Streams `RUNNING` state |

MySQL, Keycloak and RabbitMQ wait for credential initialization. API waits for all four backing services to be healthy. Reporter waits for API and the messaging/identity services to be healthy. These are `depends_on` readiness conditions, not just container-start ordering. Each application image has its own JRE-based health probe; no curl or Python installation is required in it.

The dependency conditions gate startup. They do not automatically restart applications if a later dependency becomes unhealthy. Readiness reflects broker failures; the existing outbox and reconnect behavior handle recovery. Health endpoints expose readiness status, not credentials or provider response bodies.

## End-to-end verification

After readiness succeeds:

```bash
docker compose --profile verification run --rm verify
docker compose logs contacts-api contacts-reporter
```

The opt-in verifier is Java code in the Reporter image. It performs a real Keycloak browser-form authorization-code login with S256 PKCE, exchanges the code, verifies the expected issuer, checks anonymous API rejection, creates a user-owned contact in MySQL, and observes the same event/correlation/contact IDs in RabbitMQ and a committed Kafka Reporter count. It also calls Reporter's existing HTTP endpoint to verify its service-account authentication and ownership isolation. It prints only IDs and a pass/fail summary; passwords, access tokens and provider response bodies are not printed or saved.

The verifier's RabbitMQ probe is a separate temporary exclusive queue; it does not consume messages intended for Reporter. Confirm `contact-created consumed ... result=success` in the Reporter log for the printed `eventId` to verify Reporter's RabbitMQ handling, alongside `kafka contact-created processed` for that same ID.

Default Java tests still run with `mvn clean verify` without requiring these running services. To run that same suite on Java 21/Linux inside Docker and export summaries/coverage to a directory of your choice:

```bash
docker build --progress=plain -f containers/Dockerfile.tests \
  --output type=local,dest=/tmp/contacts-test-results .
```

This test build needs neither the running stack nor a host JDK and does not change the application runtime images. Source changes invalidate its test layer. Existing isolated broker tests remain available under the `integration` profile as documented in [Kafka verification](kafka.md#tests).

## Verification recorded on October 20, 2026

The final configuration was started in the new `contacts-clean-final-20261020` Compose project with a single `up --build -d --wait` invocation, `--env-file /dev/null`, and freshly created project-scoped volumes. All six long-running services reached `healthy`; the credential initializer completed with exit code 0. No existing images or volumes were deleted. Public Maven artifacts validated against their cached checksums were used to warm the Docker dependency cache after external registry/repository connections failed intermittently. Both application images were compiled from source inside Docker; no host `target` output or private Maven settings were used. This verifies clean runtime/bootstrap behavior with a warm dependency cache, not an empty-cache Internet download.

The real Java verifier passed Keycloak authorization-code/PKCE login, token acceptance by the secured API, anonymous rejection, MySQL contact creation, RabbitMQ publication, committed Kafka consumption and service-account ownership isolation. Its first contact had ID 1 and event ID `381bdf61-40fb-464f-af3b-c06e868bd7a3`. Application logs showed both broker publications and both Reporter handlers for that same event/correlation/contact. A database check returned one contact, zero pending outbox rows and Flyway schema version 4.

The unmodified default test suite passed in the Java 21/Linux test build: **231 executed cases, zero failures, zero errors**. Three opt-in standalone broker integration cases were skipped by their existing conditions; the full real Compose verifier above was executed separately. Both existing business/endpoint coverage checks passed. The host-side test attempts encountered local TCP address errors on macOS, so the exported Linux reports are the verification evidence for this run.

Independent image checks confirmed `10001:10001` as the configured user for both images and runtime UID 10001. Images run without mounts contained no source workspace, Maven cache, environment file or generated runtime credentials. The application images were approximately 508 MiB (API) and 594 MiB (Reporter), including their Java 21 JREs and runtime dependencies.

Stopping RabbitMQ caused the API readiness probe to fail as expected. Restarting it and waiting through Compose restored all six services to `healthy`, using the existing credentials/data. The isolated test environments were then stopped to release their host ports; their images and volumes were retained. No commit, push, PR or merge was performed.

## Clean-environment verification without deleting existing data

Use a new Compose project name and no environment file. Its containers, network and all named volumes are separate from an existing project:

```bash
docker compose --env-file /dev/null -p contacts-clean up --build -d --wait
docker compose --env-file /dev/null -p contacts-clean --profile verification run --rm verify
docker compose --env-file /dev/null -p contacts-clean ps
```

The usual host ports must be free. If an existing stack is running, use unused host-port overrides (and a matching `KEYCLOAK_PUBLIC_URL`) or stop that stack deliberately first. A different project name isolates storage but does not make host ports shareable. No volume/image deletion is necessary to prove first-start initialization from a clean checkout; cached base images/dependencies only accelerate the build.

## Stop and data safety

```bash
docker compose stop
```

This stops the containers while preserving MySQL contacts, Keycloak identities/keys, broker data, generated credentials and Reporter state. Restart with `docker compose up -d --wait`. Do not delete or replace credential volumes separately from their corresponding initialized service data: generated replacement passwords would not match those existing databases. Removing images or volumes is optional cleanup with a separate impact review and requires the user's explicit approval. No Git commit, push, PR creation or merge is performed by the implementation workflow; those steps belong to the user.

References: [Quarkus container images](https://quarkus.io/guides/container-image/), [Docker multi-stage builds](https://docs.docker.com/build/building/multi-stage/), [Keycloak health checks](https://www.keycloak.org/server/health), and [Keycloak hostname/backchannel configuration](https://www.keycloak.org/server/hostname).
