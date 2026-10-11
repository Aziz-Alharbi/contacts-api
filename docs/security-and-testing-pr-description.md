# Verify API security and enforce automated test coverage

Add signed HTTP security tests for JWT claims/signatures, RBAC, admin group permissions, and contact ownership without granting admins or service accounts access to other users' contacts. Make the API test directory trackable and prevent Quarkus's signature-failure WARN logger from exposing raw JWTs.

Add mocked ContactService/ContactGroupService and Reporter service tests, plus HTTP tests for every Contact/ContactGroup endpoint. Verify CRUD data/status/Location, validation, not-found responses, pagination, duplicate constraints and rollback, and secure Reporter failures. Replace Reporter test loops with parameterized cases.

Configure shared JaCoCo instrumentation and module reports during `mvn verify`, combining plain unit and Quarkus HTTP execution. Reset stale data and fail missing data; enforce at least 80% line coverage per business/endpoint class. All targeted classes currently reach 100% line coverage.

New boundary tests found Reporter integer overflow in page/size metadata. Add explicit range checks so oversized fields cannot be truncated into acceptable values.

Validation: `mvn clean verify` and a profile-aware randomized class/method-order build pass 98 cases across both applications, including all 26 existing security cases. Controlled ownership and count mutations are detected; the source is restored automatically. An incomplete-suite check verifies the 80% gate fails. See testing-and-coverage.md for commands, reports, evidence, and scope limits. No real secrets or JWT values are included.
