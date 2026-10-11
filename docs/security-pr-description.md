# Verify authentication, RBAC, and ownership failures

Add missing API security checks for malformed JWTs, blank subjects, insufficient roles, admin-only group operations, cross-subject CRUD, owner-query rejection, ownership spoofing, and filtered contact/group lists. Existing signature, issuer, audience, and expiration checks are retained. Admins and service accounts remain subject to contact ownership.

Make the API tests trackable by removing the test-directory Git exclusion. Replace Reporter test loops with parameterized cases and assert gateway failures expose neither credentials nor fabricated counts. Restrict the OIDC provider logger to ERROR because its signature-failure WARN message can contain the raw JWT; validation policy is unchanged.

Validation: full Maven reactor passes twice (including randomized class order), with 26 cases (15 API, 11 Reporter). JWT negatives exercise actual Quarkus OIDC with generated RSA keys, and HTTP authorization tests use an isolated H2 database. Actual Keycloak + disposable MySQL integration also passed: count two, missing JWT 401, forbidden service group write 403, wrong-secret Reporter 503. See security-verification.md for the matrix, live verification command, and scope limits. See testing-and-coverage.md for the enforced coverage threshold and full test results.
