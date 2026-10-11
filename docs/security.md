# Contacts API security

All `/api/v1/*` endpoints require a bearer JWT from the `contacts` realm. Quarkus OIDC discovers the issuer and JWKS at `${KEYCLOAK_URL:http://localhost:8081}/realms/contacts`. Tokens must have a valid RS256 signature, the exact discovered issuer, an unexpired expiration, a subject, and an audience containing `contacts-api`. Roles are read from the realm's top-level `roles` array. JWT and opaque-token introspection are disabled; an invalid signature cannot fall back to remote introspection.

## Permissions

| Operation | `user` | `admin` |
|---|---|---|
| Create, list, read, update, delete contacts | Own contacts only | Own contacts only |
| List contacts belonging to a group | Own contacts only | Own contacts only |
| Read/list shared groups | Allowed | Allowed |
| Create/update/delete shared groups | Forbidden | Allowed |

The admin role manages shared group metadata; it grants no access to another subject's contact data. Groups have no owner and are shared taxonomy. Foreign key restrictions prevent deleting a group that still contains contacts. There is no administrative contact ownership transfer endpoint.

A valid token with neither application role gets **403**. Missing, malformed, expired, wrongly signed, wrong-issuer, or wrong-audience tokens get **401**. Missing subjects are rejected; empty subjects cannot operate on contacts. Reading, updating, or deleting another subject's existing contact returns **403**. Missing contact IDs return **404**. Framework security errors retain their HTTP status instead of becoming 500 errors.

## Ownership

The API derives `contacts.owner` from the verified access token's `sub`. A supplied JSON `owner` property is ignored for backward compatibility and is absent from the request contract. Creating a contact stores the caller's subject. Updating a contact preserves its existing owner. Reads, updates, and deletes check ownership in the service before mutation. Lists and counts include an owner predicate in the database query, including `/groups/{id}/contacts`; pagination happens after filtering. The optional `owner` query parameter can specify only the caller's own subject; any other value is forbidden, including for admins.

Existing rows retain their stored owner strings. Old free-form owners are not automatically assigned to current users. Before deploying over existing data, explicitly map legacy owner values to verified Keycloak subject IDs using a reviewed database migration. Never assign legacy contacts to whichever user requests them first.

The reporter service account has the `user` role. Its owner is its own service-account `sub`, so it cannot report on every human user's contacts. Any future cross-user reporting capability requires a separately reviewed permission and endpoint.

## Local run

Start Keycloak as described in [the identity setup](../identity/README.md), then run the existing MySQL-backed application:

```bash
mvn -pl contacts-api quarkus:dev
```

Set `KEYCLOAK_URL` to change the identity server's base URL. The realm remains `/realms/contacts`. Use HTTPS outside local development. JWT subjects are realm-specific; changing the issuer requires an explicit review of existing ownership data.

Obtain and save a token without printing it:

```bash
python3 identity/scripts/get_token.py user --save /tmp/contacts-user.jwt
```

Send it locally (the request has no client-provided owner):

```bash
curl -H "Authorization: Bearer $(cat /tmp/contacts-user.jwt)" \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Ali","lastName":"Example","email":"ali@example.test"}' \
  http://localhost:8080/api/v1/contacts
```

Swagger UI's Authorize button accepts the bearer token. Authentication happens in Keycloak; this project does not implement a login/password endpoint.

## Automated verification

```bash
mvn verify
```

Security tests start a local JWKS server with an explicit test issuer with freshly generated RSA keys and an in-memory H2 database in MySQL compatibility mode. They send actual signed JWTs over HTTP through Quarkus OIDC, not mocked identities. Tests cover missing/invalid tokens, expired tokens, wrong issuer/audience/signature and blank subject, insufficient roles, admin group permissions, ownership spoofing, cross-user CRUD, admin contact isolation, and owner-filtered group pagination.

A local smoke test also verified actual Keycloak discovery, reporter token claims, and the packaged API accepting that signed token, rejecting missing JWTs (401), and rejecting user-role group writes (403). Live integration checks also verified real CRUD and derived reporting with an isolated MySQL database. No test keys, tokens, or databases are used by the production profile.

References: [Quarkus bearer authentication](https://quarkus.io/guides/security-oidc-bearer-token-authentication/) and [OIDC claim validation configuration](https://quarkus.io/guides/security-oidc-expanded-configuration/).
