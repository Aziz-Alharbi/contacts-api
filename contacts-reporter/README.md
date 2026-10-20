# Contacts Reporter

Independent Quarkus reporting service with three core classes and a separate optional command-mode package, no database, and one local report endpoint:

```text
GET http://localhost:8082/reports/contacts/count
```

Example response:

```json
{"contactCount":2,"scope":"service-account"}
```

The endpoint requires no human login. The reporter authenticates outbound using the existing `contacts-reporter` confidential Keycloak client and Client Credentials. Quarkus's OIDC REST client filter acquires, caches, and renews access tokens and attaches `Authorization: Bearer ...` automatically. No custom login or token store is implemented.

## Ownership and permissions

The REST client calls only `GET /api/v1/contacts?page=0&size=1`. The Contacts API applies the verified JWT subject to its query and count. The report uses the returned `total`, not the length of the first page and not a hardcoded number. An empty result returns zero. No additional pages or client-supplied owner filters are needed for this count.

The reporter service account keeps only its existing `user` role. It has no admin role and cannot manage shared groups or access contacts owned by a human subject. A freshly created service account may legitimately see zero contacts. To demonstrate a nonzero report, create contacts through the API using a token issued to the reporter service account; ownership is then assigned by the API. No authorization/ownership bypasses are added.

## Run independently

Start Keycloak using the root Compose configuration and start MySQL and Contacts API as documented at the repository root. In a separate terminal, export settings from your local environment without committing them:

```bash
export CONTACTS_REPORTER_CLIENT_ID=contacts-reporter
export CONTACTS_REPORTER_SECRET='<your local client secret>'
export KEYCLOAK_URL=http://localhost:8081
export CONTACTS_API_URL=http://localhost:8080
mvn -pl contacts-reporter quarkus:dev
```

The secret must match the realm's `contacts-reporter` client. Keep it in your local `.env` or secret store and export it for the reporter process. Maven does not automatically load the repository `.env`. Never replace the configuration placeholder with a real secret.

Or build from the repository root and run the packaged application:

```bash
mvn verify
java -jar contacts-reporter/target/quarkus-app/quarkus-run.jar
curl http://localhost:8082/reports/contacts/count
```

The reporter binds to `127.0.0.1` by default because this assignment intentionally exposes a report without user authentication. Any external deployment should explicitly decide who may access these counts. Use HTTPS URLs outside local development. The reporter neither returns nor logs the bearer token or client secret. REST-client request/response logging is disabled.

## Required failure scenarios

| Scenario | Detector | Behavior |
|---|---|---|
| Wrong client secret | Keycloak token endpoint | Rejects Client Credentials (`invalid_client`); reporter returns a generic 503 without forwarding provider details |
| Wrong JWT audience | Contacts API's OIDC validation | Rejects the token with 401 because `aud` lacks `contacts-api`; reporter maps upstream 401 to generic 502 |
| Expired JWT | Contacts API's OIDC validation | Rejects a stale token with 401 because `exp` is in the past; reporter normally renews its token before use |
| Missing service role or forbidden operation | Contacts API's RBAC / ownership checks | Returns 403; reporter maps upstream 403 to generic 502 |
| Invalid page / upstream failure | Reporter | Returns a generic 502 (or 503 for acquisition, transport, or decoding failures); never returns a fabricated count |

`contacts-api` verifies signature, issuer, expiration, subject, and audience using Keycloak discovery/JWKS. The existing realm mapper includes `contacts-api` in service-account tokens. Decoding a JWT alone is not cryptographic verification. The reporter does not broaden role scopes or weaken API validation.

## Verification

```bash
mvn verify
```

Reporter HTTP tests use a local token endpoint and API fixture to verify automatic Client Credentials, bearer-token attachment, derived/empty counts, malformed responses, upstream 401/403/500, and wrong-secret rejection. The Contacts API's HTTP security tests separately verify wrong audience, expiration, signature and issuer using signed JWTs through its real OIDC validator.

Live integration checks should run against Keycloak and MySQL: create two reporter-owned contacts through the protected API, call the reporter without an inbound JWT, and confirm `contactCount` is two (plus any preexisting reporter-owned rows). Repeat with a deliberately wrong reporter secret and confirm failure without leaked details. For audience/expiry demonstrations, use the API test fixtures rather than editing the live realm or disabling signature checks.

References: [Quarkus OIDC client and filters](https://quarkus.io/guides/security-openid-connect-client-reference/) and [API security policy](../docs/security.md).

For step-by-step live verification and Git secret review, see [security verification](../docs/security-verification.md).

After command-package simplification, all 21 Reporter cases still pass, including normal reporting, automatic outbound credentials/bearer attachment, empty data, upstream failures, and wrong-secret rejection. Tools remain limited to identity setup, token inspection/private saves, and safe realm export sanitization; see [command reference](../identity/TOOLS.md).

## Contact-created event consumer

The Reporter also consumes RabbitMQ contact-created events and logs their IDs and result. Processing gets three total attempts before dead-lettering. This does not change the HTTP count endpoint. See [messaging setup, topology, failure demonstration, and broker tests](../docs/messaging.md).
