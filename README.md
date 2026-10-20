# contacts-api

A Quarkus REST API for managing contacts and contact groups with validation, pagination, and filtering.

## Run the complete system

Install Docker with Docker Compose, then run from a clean checkout:

```bash
docker compose up --build
```

This builds and runs Contacts API, Contacts Reporter, MySQL, Keycloak, RabbitMQ and Kafka. No local Java/Maven, prebuilt JAR, `.env`, database creation or manual credentials setup is required. A one-time helper generates random local credentials in a Docker volume. Services wait for real readiness checks; both applications run as UID 10001.

API: <http://localhost:8080>, Reporter: <http://localhost:8082>, Keycloak: <http://localhost:8081>, RabbitMQ UI: <http://localhost:15672>. Application readiness is available at `/q/health/ready` on each application's port.

For detached startup with a readiness wait and the Java end-to-end check:

```bash
docker compose up --build -d --wait
docker compose --profile verification run --rm verify
```

See [container setup, generated credentials, clean-environment verification, and data safety](docs/containers.md). The Maven instructions below remain available for running the applications on the host.

## Project structure

The root `pom.xml` is the parent and reactor POM. It manages shared Java, dependency, and plugin settings.

- `contacts-events/` contains the shared event contract and RabbitMQ/Kafka transports.
- `contacts-api/` contains the existing Quarkus application and Dockerfiles.
- `docs/openapi.yaml` remains the exported API contract.
- `contacts-reporter/` is the independent, database-free reporting application. See [reporter setup](contacts-reporter/README.md) for Client Credentials, the contact count endpoint, and failure scenarios.

Run `mvn verify` from the repository root to build all modules. To build only the API and its parent, use `mvn -pl contacts-api -am verify`.

Docker builds use the repository root as their context and compile the application in a builder stage:

```bash
docker build -f contacts-api/src/main/docker/Dockerfile.jvm -t contacts-api .
docker build -f contacts-reporter/src/main/docker/Dockerfile.jvm -t contacts-reporter .
```

## RabbitMQ events

Contact creation writes a transactional outbox event. The Reporter consumes it with three processing attempts and a dead-letter queue. See [RabbitMQ setup, event design, retry behavior, and tests](docs/messaging.md). Start the development broker with `docker compose up -d --wait rabbitmq`, then run `mvn install` once before starting individual modules in development mode.

## Kafka events

Contact creation publishes the same outbox event to RabbitMQ and Kafka, with independent broker delivery flags. Kafka uses `contactId` keys and the Reporter uses Kafka Streams with durable deduplication and transactional counts. See [Kafka setup, ordering, recovery, and real-broker tests](docs/kafka.md). Start both development brokers with `docker compose up -d --wait kafka rabbitmq`.

## Requirements for running on the host

- Java 21
- Maven
- MySQL 8
- Docker and Docker Compose for Keycloak and optional live verification

## Identity provider

The Docker-based Keycloak setup, realm configuration, users, service account, and JWT examples are documented in [identity/README.md](identity/README.md).

## API security

All API endpoints require a Keycloak access token. Ordinary users see only contacts owned by their verified JWT subject. Administrators can read all contacts and are the only role permitted to delete contacts or groups. Contact creation assigns the caller as owner; updates retain the ownership restriction. See [docs/security.md](docs/security.md) for permissions, token validation, legacy ownership handling, and tests.

## Database Setup

Create a MySQL database:

```sql
CREATE DATABASE contacts_db;
```

Set the database password before running the application:

```bash
export DB_PASSWORD=your_mysql_password
```

Flyway migrations run automatically when the application starts and create the required database schema.

## Running the Application

Start the application in development mode:

```bash
mvn -pl contacts-api quarkus:dev
```

The API runs at:

```text
http://localhost:8080
```

## Testing the API

Swagger UI can be used to test all API endpoints:

```text
http://localhost:8080/q/swagger-ui/
```

The exported OpenAPI contract is available at:

```text
docs/openapi.yaml
```


## Design Decisions

- The application uses a layered architecture with separate resource, service, repository, entity, and DTO packages.
- Resources handle HTTP requests and delegate business logic to services.
- DTOs are used for API requests and responses so JPA entities are not exposed directly.
- Panache repositories are used for database access.
- Flyway manages the database schema, and Hibernate automatic schema generation is disabled.
- A contact may belong to one contact group, and the group is optional.
- Contact email and contact group name uniqueness are enforced by the database.
- Contact and group listing supports pagination and filtering.
- API errors are handled centrally and use a consistent `status`, `error`, and `message` response structure.
- The API is versioned under `/api/v1`.

## Packaging and running the application

The application can be packaged using:

```shell script
mvn -pl contacts-api -am package
```

It produces the `quarkus-run.jar` file in the `contacts-api/target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `contacts-api/target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar contacts-api/target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
mvn -pl contacts-api -am package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar contacts-api/target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
mvn -pl contacts-api -am package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
mvn -pl contacts-api -am package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./contacts-api/target/contacts-api-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Automated tests and coverage

Run `mvn clean verify` to execute API/Reporter tests, generate per-module JaCoCo reports, and enforce 80% line coverage for business logic and endpoints. See [testing and coverage](docs/testing-and-coverage.md) for scope, report paths, and mutation verification.

## Identity and verification commands

Java 21 / Quarkus command-mode utilities live in the existing Reporter module. Build their separate command package with `mvn -pl contacts-reporter package -Dquarkus.profile=tools -DskipTests`. See [identity/TOOLS.md](identity/TOOLS.md) for Device Authorization Flow with demo-user/demo-admin, service tokens, realm export sanitization. Review procedures for Git secrets, controlled defects, and real-service checks are in [docs/security-verification.md](docs/security-verification.md) and [docs/testing-and-coverage.md](docs/testing-and-coverage.md).

Automated `mvn clean verify` tests run without external MySQL or Keycloak. For the complete system on a new machine, use `docker compose up --build` as documented above. For optional host execution, build the command package, create `.env` with `init-env`, start Keycloak using Compose, prepare MySQL and `DB_PASSWORD`, then start Contacts API and Contacts Reporter as documented above and in [contacts-reporter/README.md](contacts-reporter/README.md). Real-service verification is documented separately in [docs/security-verification.md](docs/security-verification.md).
