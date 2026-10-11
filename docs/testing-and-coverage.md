# Automated testing and coverage

## Run

From the repository root, with Java 21 and Maven:

```bash
mvn clean verify
mvn clean verify -Dsurefire.runOrder=random \
  '-Djunit.quarkus.orderer.secondary-orderer=org.junit.jupiter.api.ClassOrderer$Random' \
  '-Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random' \
  -Djunit.jupiter.execution.order.random.seed=20261011
```

The automated suite needs no Docker, MySQL, live Keycloak, or real client secret. API HTTP tests use H2 in MySQL compatibility mode and a local JWKS server with temporary RSA keys. Reporter HTTP tests use local token/API fixtures. Service unit tests instantiate the services directly with Mockito repositories, JWT identity, or API client mocks; they do not start Quarkus or connect to a database. Fixtures contain explicitly fake credentials only.

## Test inventory

| Test class | Cases | Purpose |
|---|---:|---|
| `ContactsSecurityTest` | 15 | JWT rejection, roles, admin permissions, ownership isolation, filtered lists |
| `ApiEndpointsTest` | 31 | Every Contact/ContactGroup endpoint, CRUD fields/status/Location, pagination, validation, missing records, duplicate creates/updates and transaction rollback, group deletion blocked by referenced contacts |
| `ContactServiceTest` | 21 | CRUD mapping, verified owner assignment, ownership rejection before mutation, repository query/count arguments, missing contacts/groups, persistence failure propagation |
| `ContactGroupServiceTest` | 10 | CRUD mapping, timestamps, filtered/unfiltered page totals, missing groups, duplicate persistence failure propagation |
| `ReporterTest` | 10 | Automatic credentials and bearer attachment, real response total, empty data, sanitized upstream failures, malformed data |
| `WrongSecretTest` | 1 | Failed token acquisition returns generic failure without credentials or fabricated counts |
| `ReportServiceTest` | 10 | Minimal fetch, total rather than page length, transport failure, null data, invalid/overflowing metadata |
| **Total** | **98** | Existing security checks plus missing functional/unit cases |

Duplicate uniqueness is enforced by the database and mapped to 409 by the exception handler. Mocked service tests verify persistence failures propagate; HTTP tests verify the actual constraint, wrapped-exception mapping, and rollback behavior. Request field validation belongs to the REST boundary and is verified there. Unit tests do not pretend that calling a plain Java object executes Quarkus security/transaction/validation interceptors; signed HTTP tests exercise those controls.

New test bodies use explicit assertions or JUnit parameterized cases, without loops, conditionals, or sleeps. Setup/HTTP/JWKS helpers construct fixtures and requests. Random class ordering uses Quarkus's secondary orderer to preserve required profile/resource grouping; methods are independently shuffled. Each API scenario uses distinct subjects, emails, and group names; it does not depend on another test's records or execution order. Tests assert response fields and persisted state as well as status codes.

## Coverage gate

JaCoCo's Quarkus test extension instruments HTTP-test classes. The Maven JaCoCo agent collects ordinary unit-test execution, excluding the Quarkus classloader to avoid double instrumentation. Their execution data is combined per module. At initialize, Maven removes the prior execution file so `verify` cannot accumulate stale coverage. At verify, missing coverage data fails the build, reports are generated, and JaCoCo requires **at least 80% line coverage for each included class**.

Coverage scope includes all API classes under `com.unifonic.contacts.service` and `com.unifonic.contacts.resource`, plus Reporter `ReportService`, its report record, and `ReportResource`. DTO/entity accessors, repositories, exception infrastructure, the abstract REST client interface, generated Quarkus classes, and test fixtures are outside this assignment gate. Business logic and endpoints are not excluded. Class-level enforcement is stricter than a combined package average.

Reports are generated automatically:

- `contacts-api/target/jacoco-report/index.html` and `jacoco.xml`
- `contacts-reporter/target/jacoco-report/index.html` and `jacoco.xml`

Measured coverage after the complete suite on October 11, 2026:

| Class | Covered / total lines | Coverage |
|---|---:|---:|
| ContactService | 81 / 81 | 100% |
| ContactGroupService | 36 / 36 | 100% |
| ContactResource | 10 / 10 | 100% |
| ContactGroupResource | 11 / 11 | 100% |
| ReportService | 21 / 21 | 100% |
| ReportResource | 2 / 2 | 100% |
| ContactCount record | 1 / 1 | 100% |

Line coverage does not guarantee correctness or complete branch coverage. The suite includes boundary tests, state assertions, and deliberate mutations to address that limitation.

## Detected defect and mutations

New Reporter metadata cases exposed an actual integer-overflow bug: `page=4294967296` or `size=4294967297` could truncate to the expected values via Jackson `asInt`. Both tests failed before the fix. `canConvertToInt()` checks now reject these responses rather than returning a count.

Run the controlled mutation check explicitly:

```bash
python3 scripts/check_test_mutations.py
mvn clean verify
```

The script temporarily disables contact ownership enforcement and replaces Reporter total with first-page item count. Each selected regression test must fail with an assertion (not compilation/startup errors). Source files are restored in `finally`; the final clean build restores compiled classes and reports. Mutation logs stay under ignored `target/` directories. Run this only while no other process is editing the same sources.

To demonstrate enforcement with intentionally insufficient coverage:

```bash
mvn -pl contacts-api clean verify -Dtest=ContactGroupServiceTest
```

That command must fail the coverage gate for ContactService and the resources. Follow it with the complete clean build to regenerate the complete report.

## Security and integration scope

The [security verification matrix](security-verification.md) documents the unchanged 401/403 and ownership contract, the logger change that prevents raw JWT signature-failure messages, and real Keycloak/MySQL smoke verification. No signing keys, bearer JWT values, local credentials, databases, build outputs, or mutation files belong in the commit.

The automated HTTP tests exercise the running application in JVM test mode, not a separate packaged process. The disposable packaged-app check remains available through `python3 contacts-reporter/scripts/verify_e2e.py`; it needs Docker and the local realm/secret. Production deployment, TLS/ingress policy, and whole-project coverage beyond the specified packages are outside this gate.

Coverage configuration follows [Quarkus's JaCoCo guidance](https://quarkus.io/guides/tests-with-coverage/).

## Verified results

On October 11, 2026, the complete clean build and the profile-aware randomized build both passed all 98 cases with the coverage gate satisfied. The incomplete group-service-only build failed specifically on coverage for the untested ContactService and resources. Both mutation checks produced one assertion failure each and restored source files; the final clean build rebuilt the original implementation. Local credential/JWT and whitespace scans passed.
