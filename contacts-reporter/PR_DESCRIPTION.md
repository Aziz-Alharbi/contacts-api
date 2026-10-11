# Implement Contacts Reporter with OAuth2 Client Credentials

Adds a separate, database-free Quarkus `contacts-reporter` module with three production classes. `GET /reports/contacts/count` obtains a service token automatically and derives `contactCount` from the secured API's owner-filtered page total. The report can be called locally without human login and returns zero for an empty authorized dataset.

The existing confidential Keycloak client keeps only its `user` role. Client ID/secret and upstream URLs are configurable through environment variables. The REST client OIDC filter caches and renews tokens and attaches bearer credentials automatically. No API authorization, audience validation, or contact ownership restrictions are weakened. Upstream 401/403 and invalid pages produce generic gateway failures; token acquisition/network failures produce generic 503 responses without exposed provider bodies or credentials.

Validation:
- Full `mvn verify` reactor build: passes.
- Five API security scenarios and five reporter HTTP integration tests: pass, covering signature/issuer/audience/expiration rejection, valid service-token acceptance, automatic client credentials and bearer attachment, derived/empty counts, malformed data, upstream failures, and invalid credentials.
- Actual Keycloak + isolated MySQL 8.4 + independently launched packaged API/Reporter: created two reporter-owned contacts and obtained count two without inbound login.
- Actual Keycloak rejected wrong secret; Reporter returned 503. API returned 401 for missing JWT and 403 for a service-account group write.
- Local secret scan and diff whitespace checks: pass.

Includes run instructions, failure explanations, and a reproducible disposable end-to-end verification script. The report counts only the service account's own contacts, not every human user's contacts. Reporter binds to loopback by default. No credentials or real tokens are included in the changes.
