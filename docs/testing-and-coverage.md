# Automated testing and coverage

## Run

From the repository root, with Java 21 and Maven:

```bash
mvn clean verify
mvn clean verify -Dsurefire.runOrder=random \
  '-Djunit.quarkus.orderer.secondary-orderer=org.junit.jupiter.api.ClassOrderer$Random' \
  '-Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random' \
  -Djunit.jupiter.execution.order.random.seed=20261012
```

The automated suite needs no Docker, MySQL, live Keycloak, or real client secret. API HTTP tests use H2 in MySQL compatibility mode and a local JWKS server with temporary RSA keys. Reporter HTTP tests use local token/API fixtures. Service unit tests instantiate the services directly with Mockito repositories, JWT identity, or API client mocks; they do not start Quarkus or connect to a database. Fixtures contain explicitly fake credentials only.

## Test inventory

| Test class | Cases | Purpose |
|---|---:|---|
| `ContactsSecurityTest` | 30 | JWT rejection, roles, admin permissions, ownership isolation, filtered lists |
| `HttpErrorsTest` | 32 | All endpoint 401/403 responses, consistent JSON errors, authentication challenges, method headers, malformed payloads |
| `ApiEndpointsTest` | 31 | Every Contact/ContactGroup endpoint, CRUD fields/status/Location, pagination, validation, missing records, duplicate creates/updates and transaction rollback, group deletion blocked by referenced contacts |
| `ContactServiceTest` | 21 | CRUD mapping, verified owner assignment, ownership rejection before mutation, repository query/count arguments, missing contacts/groups, persistence failure propagation |
| `ContactGroupServiceTest` | 10 | CRUD mapping, timestamps, filtered/unfiltered page totals, missing groups, duplicate persistence failure propagation |
| `ReporterTest` | 10 | Automatic credentials and bearer attachment, real response total, empty data, sanitized upstream failures, malformed data |
| `WrongSecretTest` | 1 | Failed token acquisition returns generic failure without credentials or fabricated counts |
| `ReportServiceTest` | 10 | Minimal fetch, total rather than page length, transport failure, null data, invalid/overflowing metadata |
| `RealmConfigurationTest` | 28 | Realm/client/role/device configuration and safe export sanitization |
| `OAuthTokensTest` | 27 | Device polling/PKCE, client credentials, sanitized failures, JWT inspection and reporter claims |
| `LocalFilesTest` | 7 | Private files, overwrite/symlink rejection, environment overrides and invalid command |
| **Total** | **207** | API, Reporter, identity and utility tests |

Duplicate uniqueness is enforced by the database and mapped to 409 by the exception handler. Mocked service tests verify persistence failures propagate; HTTP tests verify the actual constraint, wrapped-exception mapping, and rollback behavior. Request field validation belongs to the REST boundary and is verified there. Unit tests do not pretend that calling a plain Java object executes Quarkus security/transaction/validation interceptors; signed HTTP tests exercise those controls.

New test bodies use explicit assertions or JUnit parameterized cases, without loops, conditionals, or sleeps. Setup/HTTP/JWKS helpers construct fixtures and requests. Random class ordering uses Quarkus's secondary orderer to preserve required profile/resource grouping; methods are independently shuffled. Each API scenario uses distinct subjects, emails, and group names; it does not depend on another test's records or execution order. Tests assert response fields and persisted state as well as status codes.

## Coverage gate

JaCoCo's Quarkus test extension instruments HTTP-test classes. The Maven JaCoCo agent collects ordinary unit-test execution, excluding the Quarkus classloader to avoid double instrumentation. Their execution data is combined per module. At initialize, Maven removes the prior execution file so `verify` cannot accumulate stale coverage. At verify, missing coverage data fails the build, reports are generated, and JaCoCo requires **at least 80% line coverage for each included class**.

Coverage scope includes all API classes under `com.unifonic.contacts.service` and `com.unifonic.contacts.resource`, plus Reporter `ReportService`, its report record, and `ReportResource`. DTO/entity accessors, repositories, exception infrastructure, the abstract REST client interface, generated Quarkus classes, and test fixtures are outside this assignment gate. Business logic and endpoints are not excluded. Class-level enforcement is stricter than a combined package average.

Reports are generated automatically:

- `contacts-api/target/jacoco-report/index.html` and `jacoco.xml`
- `contacts-reporter/target/jacoco-report/index.html` and `jacoco.xml`

Measured coverage after the complete suite on October 12, 2026:

| Class | Covered / total lines | Coverage |
|---|---:|---:|
| ContactService | 84 / 84 | 100% |
| ContactGroupService | 36 / 36 | 100% |
| ContactResource | 10 / 10 | 100% |
| ContactGroupResource | 11 / 11 | 100% |
| ReportService | 21 / 21 | 100% |
| ReportResource | 2 / 2 | 100% |
| ContactCount record | 1 / 1 | 100% |

Line coverage does not guarantee correctness or complete branch coverage. The suite includes boundary tests, state assertions, and deliberate mutations to address that limitation.

## Detected defect and mutations

New Reporter metadata cases exposed an actual integer-overflow bug: `page=4294967296` or `size=4294967297` could truncate to the expected values via Jackson `asInt`. Both tests failed before the fix. `canConvertToInt()` checks now reject these responses rather than returning a count.

To demonstrate defect detection manually, temporarily replace `page.get("total").longValue()` with `page.get("items").size()` in Reporter `ReportService`. Run `mvn -pl contacts-reporter -Dtest=ReportServiceTest#countFetchesOnlyOneItemAndUsesTotalRatherThanPageLength test` and require an assertion failure. Restore that exact edit immediately, preserving other local changes, then run `mvn clean verify`. For ownership, temporarily bypass the comparison in `ContactService.requireOwnership` and run `mvn -pl contacts-api -Dtest=ContactServiceTest#foreignUpdateDoesNotMutateRecord test`; require an assertion failure and restore the comparison. Perform these checks only while no other process is editing those sources. A compilation/startup failure does not prove defect detection.

To demonstrate enforcement with intentionally insufficient coverage:

```bash
mvn -pl contacts-api clean verify -Dtest=ContactGroupServiceTest
```

That command must fail the coverage gate for ContactService and the resources. Follow it with the complete clean build to regenerate the complete report.

## Security and integration scope

The [security verification matrix](security-verification.md) documents the 401/403 and ownership contract, the logger change that prevents raw JWT signature-failure messages, and real Keycloak/MySQL smoke verification. No signing keys, bearer JWT values, local credentials, databases, build outputs, or mutation files belong in the commit.

The automated HTTP tests exercise the running application in JVM test mode, not a separate packaged process. Manual packaged-app verification is documented in [security verification](security-verification.md); it needs MySQL, Docker/Keycloak, and local credentials. Production deployment, TLS/ingress policy, and whole-project coverage beyond the specified packages are outside this gate.

Coverage configuration follows [Quarkus's JaCoCo guidance](https://quarkus.io/guides/tests-with-coverage/).

## Verified results

On October 14, 2026, `mvn clean verify` passed all 216 Java cases before simplification, with 100% line coverage for every class in the business/endpoint coverage gate. The earlier randomized build passed the existing API/Reporter suite. The earlier incomplete-suite and controlled-mutation checks remain documented above; they were not repeated during this permission update. Identity configuration checks are now JUnit tests in the existing Reporter module; see the Java command reference. See the security verification document for the complete local Git object audit and its limits.

## Java identity utility verification

The existing Reporter module also hosts the named Quarkus command entry point. Its 62 JUnit utility cases replace the former external identity checks and add deterministic Device Flow, PKCE, failure handling, private-file, and realm sanitization cases. Use the [Java command reference](../identity/TOOLS.md) to build the separate tools package. These utility classes do not replace or exclude any business/endpoint class from the existing coverage gate.

After utility simplification on October 14, 2026, `mvn clean verify` passed 207 cases: 124 API, 21 Reporter, and 62 identity/file/command utility cases. All business/endpoint classes retained 100% line coverage and the 80% Maven gate passed. Only the nine tests for the removed history-scanner implementation were removed; application and security tests were retained.

The rebuilt tools package was exercised against a temporary OAuth HTTP fixture using its actual `token user`, `token admin`, and `token reporter` commands. All three obtained a token, printed selected claims without printing the bearer/secret, and saved exactly the received token with mode 0600. Device requests used matching S256 PKCE and service requests used Client Credentials. Packaged environment initialization and realm sanitization also completed with private output files. These checks use fixture tokens, not real Keycloak browser sessions.

The normal packaged Reporter was also started independently in the production profile against temporary OAuth/API fixtures. It acquired its service token through Quarkus, attached the Bearer header, and returned the fixture API's real total of three rather than the one-item page length. It was stopped after verification. The temporary harness was kept outside the repository; no new application service or dependency was added.

## RabbitMQ event tests

See [messaging verification](messaging.md#repeatable-tests) for the new outbox/HTTP atomicity, publisher, bounded retry, malformed payload, and ACK error tests, plus the opt-in real RabbitMQ test. Default tests disable messaging jobs and require no broker. The existing business/endpoint coverage gate is preserved.

## Kafka and dual-broker verification

See [Kafka tests and recovery](kafka.md#tests) for producer key/confirmation checks, durable event-ID deduplication, contact ordering, independent outbox delivery flags, and opt-in Kafka/RabbitMQ tests. Kafka jobs are disabled in the default test and identity tools profiles. The real-broker tests separately verify HTTP creation/dual publication and Streams restoration into a fresh local state directory.

## Container verification

The [container guide](containers.md#end-to-end-verification) documents the real six-service Compose check and a separate Java 21/Linux build of this same test suite with exported summaries and coverage. `ContainerSupportTest` adds random credential generation, restart preservation, malformed-file rejection and symlink rejection checks. Application readiness also checks the real brokers and Reporter's Kafka Streams state.
