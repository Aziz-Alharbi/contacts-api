# Security verification

## Automated checks

On October 12, 2026, `mvn clean verify` and a build with profile-aware randomized class/method ordering each passed 145 Java test cases: 124 Contacts API cases and 21 Reporter cases. API tests send signed RS256 tokens through the real Quarkus OIDC validator with fresh, temporary RSA keys and an isolated H2 database. Reporter tests use a local OAuth2/API fixture. No production signing keys or stored JWTs are used.

| Scenario | Expected and verified result |
|---|---|
| Missing or malformed bearer token | API 401 |
| Wrong signature, issuer, audience, or expired token | API 401 |
| Blank subject | API 401 |
| Valid token without user/admin role | API 403 |
| User/service account creates, updates, or deletes a shared group | API 403; group remains readable |
| Admin creates, updates, deletes a shared group | 201 / 200 / 204; subsequent read 404 |
| Ordinary user/service account reads or updates a foreign contact | API 403; original record remains unchanged |
| Ordinary user/service account deletes any contact | API 403, including own contacts |
| Admin reads/deletes a foreign contact | API 200 / 204 |
| Admin updates a foreign contact | API 403; ownership is preserved |
| Explicit query for another owner | User 403; admin 200 with matching records |
| User A and User B contact/group-contact lists | Only caller-owned records and totals, even with page size one |
| Admin contact/group-contact lists | Records and totals across all owners |
| Client-supplied owner on contact creation | Ignored; stored owner equals validated sub |
| Reporter wrong client secret | Token endpoint rejects; Reporter returns generic 503 |
| Reporter receives API 401 / 403 / 500 | Generic 502 without token, secret, provider body, or fabricated count |
| Malformed or inconsistent API report data | 502 or 503 without fabricated count |

Reporter audience and expiry checks reuse the API tests' service-subject tokens rather than duplicating the OIDC tests. Keycloak detects invalid client credentials; Contacts API detects invalid JWT claims/signatures. Reporter translates upstream failures into generic gateway responses.

Existing Reporter loops were replaced with parameterized cases; test bodies contain no loops or sleeps. API subjects and fixture names are distinct for each case, so tests do not rely on execution order. The API test directory was previously ignored by Git; that exclusion was removed to make these checks reproducible in fresh checkouts.

## Logging

The Quarkus OIDC provider can include a raw JWT in its WARN signature-failure message. Its log category is set to ERROR to prevent that exposure. Signature, issuer, audience, expiry, roles, and ownership enforcement remain unchanged. Reporter response tests explicitly reject fixture tokens/secrets and sensitive upstream messages.

## Live integration

Use a separate local test database. Start Keycloak and Contacts API against that database, then start the normal Reporter package with its secret exported in the environment.

1. Obtain a reporter token using the retained `token reporter --save /tmp/reporter-review.jwt` Java command.
2. Use the saved Bearer token in Swagger UI to create two service-owned contacts; do not supply an owner.
3. Call `curl http://localhost:8082/reports/contacts/count` without an inbound JWT. Verify the returned count against the service-owned contact total, including any existing records.
4. Call the API without a token and expect 401. Use the reporter token to attempt a shared-group write and expect 403.
5. Restart Reporter with an intentionally incorrect local secret and expect a generic 503; restore the correct environment setting afterward.
6. Review wrong-audience and expired-token API tests and demonstrate them using temporary signed test fixtures, never by weakening production validation.

The earlier real-service scenario passed on October 11, 2026. The steps above are the current manual procedure and are separate from automated tests.

## Limits

This matrix covers the negative security contract. The full suite and enforced coverage threshold are documented in [testing and coverage](testing-and-coverage.md). Audience/expiry/signature/issuer negatives use generated signed fixtures, while the live integration uses actual Keycloak for Client Credentials. Production ingress/TLS and secret-store deployment are outside this local verification.

## HTTP error contract

Every API route is tested separately for missing-token 401 and unrelated-role 403. HTTP failures return JSON containing `status`, `error`, and `message`. Tests also verify invalid-token 401, missing resource/URL 404, method 405 with `Allow`, malformed JSON 400, unsupported media 415, and validation violations. OIDC authentication continues to validate signature, issuer, audience, expiration, and subject; JSON handling preserves the bearer challenge. Reporter upstream failures use the same three error fields and generic messages.

## Realm and Git audit

`mvn -pl contacts-reporter -Dtest="com.unifonic.tools.*Test" test` covers 62 Java utility cases. They check the contacts realm, API/public PKCE and Reporter/confidential clients, service-account binding, user/admin roles, restricted Reporter scope, audience/role mappers, token lifespan, disabled password grants, secret placeholders, and export sanitization. This is static configuration verification; the previous live check is recorded above.

Git secret review is a separate review procedure, described below. The October 14 Java audit inspected 30 commits, 302 trees, and 185 blobs, including available tags, stashes, reflog history, and unreferenced objects. No secret findings remained after placeholder/literal analysis. Output contains object IDs and rule names, never secret values. Patterns cover private keys, bearer JWTs, common provider tokens, credential URLs, literal credentials, tracked local environment files, and known local environment secrets.

Pattern scanning cannot prove that every possible secret is absent. Remote-only objects and objects already removed from the local object database cannot be inspected without obtaining them. This audit used the entire available local Git object database, not just the current checkout.

On October 14, 2026, the normal full reactor build passed all 216 Java tests (124 API and 92 Reporter/utility cases). The command package previously launched successfully without a Reporter secret or an HTTP listener. The earlier local object-database audit found no secret candidates under its detection rules. Browser login still needs a human to approve the device code in a running Keycloak realm; automated fixtures verify the grant, PKCE, polling and terminal failure handling.

## Git secret review procedure

No secret scanner is added to Maven, Quarkus, or the application. Use a standalone Gitleaks executable as a review tool when available; its [official documentation](https://github.com/gitleaks/gitleaks) covers Git history scanning and redaction.

From the repository root:

```bash
git ls-files .env '*.jwt' identity/exports
gitleaks git --redact --log-opts="--all --reflog" .
git fsck --full --unreachable --no-reflogs
```

The first command must return no private environment files, JWTs, or raw exports. The second checks all available branch/tag/stash references and reflog revisions, rather than only the current checkout. Review each finding locally and distinguish explicitly fake test values from real credentials; do not blanket-ignore findings or mark the check successful when the executable is unavailable.

The final command identifies stored objects not covered by references/reflogs. Scan each reported unreachable commit with `gitleaks git --redact --log-opts="COMMIT_ID" .`. For an isolated blob, use `git cat-file blob BLOB_ID | gitleaks stdin --redact`; for an unreachable tree, inspect its blob IDs with `git ls-tree -r TREE_ID` and scan their content the same way. Scan available commit/tag messages as well using `git cat-file -p OBJECT_ID | gitleaks stdin --redact`. Keep values redacted and retain only reviewed evidence outside tracked files. Do not run Git cleanup before inspecting these objects.

Before committing, review `git diff --cached --name-only` and the staged changes locally. Keep credentials in environment variables or ignored `.env`, and preserve credential placeholders in the realm export. Repeat the history scan after creating the commit. A clean pattern scan is evidence within its rules, not proof that every possible secret is absent. Remote-only history or objects already deleted from this clone are outside the available scan scope.

The simplified build passed 207 cases with unchanged application/security behavior and 100% line coverage in the required classes. The standalone Gitleaks executable was not installed in this environment, so its documented procedure was not executed during simplification; the earlier local-object scan above remains historical evidence, not a new scan result.

Post-simplification packaged checks passed for user/admin Device Flow with matching PKCE, reporter Client Credentials, selected-claim output, mode-0600 token saves, and normal Reporter automatic outbound authentication/report derivation. These were local fixture checks; no real Keycloak browser login was performed in this run.
