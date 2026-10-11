# Security verification

## Automated checks

`mvn verify` and a repeat with `-Dsurefire.runOrder=random` both passed 26 test cases: 15 Contacts API cases and 11 Reporter cases. API tests send signed RS256 tokens through the real Quarkus OIDC validator with fresh, temporary RSA keys and an isolated H2 database. Reporter tests use a local OAuth2/API fixture. No production signing keys or stored JWTs are used.

| Scenario | Expected and verified result |
|---|---|
| Missing or malformed bearer token | API 401 |
| Wrong signature, issuer, audience, or expired token | API 401 |
| Blank subject | API 401 |
| Valid token without user/admin role | API 403 |
| User/service account creates, updates, or deletes a shared group | API 403; group remains readable |
| Admin creates, updates, deletes a shared group | 201 / 200 / 204; subsequent read 404 |
| Other user, admin, or service account reads, updates, deletes a foreign contact | API 403; original record remains unchanged |
| Explicit query for another owner | API 403, including admins |
| User A, User B, and admin contact/group lists | Only caller-owned records and totals, even with page size one |
| Client-supplied owner on contact creation | Ignored; stored owner equals validated sub |
| Reporter wrong client secret | Token endpoint rejects; Reporter returns generic 503 |
| Reporter receives API 401 / 403 / 500 | Generic 502 without token, secret, provider body, or fabricated count |
| Malformed or inconsistent API report data | 502 or 503 without fabricated count |

Reporter audience and expiry checks reuse the API tests' service-subject tokens rather than duplicating the OIDC tests. Keycloak detects invalid client credentials; Contacts API detects invalid JWT claims/signatures. Reporter translates upstream failures into generic gateway responses.

Existing Reporter loops were replaced with parameterized cases; test bodies contain no loops or sleeps. API subjects and fixture names are distinct for each case, so tests do not rely on execution order. The API test directory was previously ignored by Git; that exclusion was removed to make these checks reproducible in fresh checkouts.

## Logging

The Quarkus OIDC provider can include a raw JWT in its WARN signature-failure message. Its log category is set to ERROR to prevent that exposure. Signature, issuer, audience, expiry, roles, and ownership enforcement remain unchanged. Reporter response tests explicitly reject fixture tokens/secrets and sensitive upstream messages.

## Live integration

Run `python3 contacts-reporter/scripts/verify_e2e.py` after building, with local Keycloak and Docker available. It launches disposable MySQL and independent packaged API/Reporter processes, creates two service-owned contacts, verifies count two without human login, missing API JWT 401, service group-write 403, and wrong-secret Reporter 503. It cleans up its database and processes without modifying the live realm or existing MySQL data. This live check passed on October 11, 2026.

## Limits

This matrix covers the negative security contract. The full suite and enforced coverage threshold are documented in [testing and coverage](testing-and-coverage.md). Audience/expiry/signature/issuer negatives use generated signed fixtures, while the live integration uses actual Keycloak for Client Credentials. Production ingress/TLS and secret-store deployment are outside this local verification.
