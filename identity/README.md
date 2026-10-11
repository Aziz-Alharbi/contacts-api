# Keycloak identity setup

This local development setup runs Keycloak 26.8.0 on `http://localhost:8081`, leaving port 8080 for the API. It imports the `contacts` realm from `realm/contacts-realm.json`. Keycloak stores its development database in a named Docker volume.

## Start

Install Docker with Docker Compose, then run from the repository root:

```bash
python3 identity/scripts/init_env.py
docker compose up -d keycloak
docker compose logs -f keycloak
```

Wait for Keycloak to finish realm import and report that it has started. Open `http://localhost:8081/admin/`. Sign in with `KC_BOOTSTRAP_ADMIN_USERNAME` and `KC_BOOTSTRAP_ADMIN_PASSWORD` from your local `.env`, then select the **contacts** realm.

If `.env` already exists (for example, for MySQL), the generator leaves it untouched. Add the four missing identity credential variables listed in `.env.example`, each with a different randomly generated value, and the bootstrap username. Compose reads `.env` automatically. Never commit this file.

Use `docker compose down` to stop the server while preserving its data. This uses Keycloak's development mode and is intended for local use. A deployment requires a production database, HTTPS, and production server configuration.

## Realm, clients, users, and roles

| Item | Configuration |
|---|---|
| Realm | `contacts`; isolated from Keycloak's administrative `master` realm |
| `contacts-api` | Public OIDC client; authorization code with PKCE and device login for local demonstrations; no client secret or password grant |
| `contacts-reporter` | Confidential OIDC client; client credentials grant; no interactive login |
| `demo-user` | Realm role `user`; password supplied by `CONTACTS_USER_PASSWORD` |
| `demo-admin` | Realm roles `user` and `admin`; password supplied by `CONTACTS_ADMIN_PASSWORD` |
| Reporter service account | Realm role `user`; no `admin` role |

These are application roles. The application's `admin` role does not grant access to the Keycloak admin console. Inspect clients under **Clients**, people under **Users**, and roles under **Realm roles**. The reporter's identity appears under its client's **Service account roles** tab.

The reporter's allowed role scope is explicitly limited to `user`. Both clients have an audience mapper for `contacts-api` and a realm-role mapper producing a top-level `roles` array. Keycloak also exposes its standard nested role claims.

## Obtain JWT access tokens

For the reporter:

```bash
python3 identity/scripts/get_token.py reporter
```

The script posts `grant_type=client_credentials`, `client_id=contacts-reporter`, and the secret loaded from `.env` to `http://localhost:8081/realms/contacts/protocol/openid-connect/token`.

For a human user:

```bash
python3 identity/scripts/get_token.py user
```

Open the URL printed by the script, enter its one-time code, and log in as `demo-user` or `demo-admin`. Keycloak handles the login; the script never receives the person's password. It polls the token endpoint until the login is complete or expires.

Both commands print selected decoded claims, not the bearer token:

| Claim | Meaning / expected value |
|---|---|
| `sub` | Stable identity ID; a human user's ID or the reporter service account's ID |
| `iss` | `http://localhost:8081/realms/contacts` |
| `aud` | Contains `contacts-api`; may be a string or an array |
| `exp` | Unix timestamp when the access token expires; configured lifespan is 300 seconds |
| `roles` | `user` for demo-user/reporter; `user` and `admin` for demo-admin |

Decoding does not verify a token. An API must verify the signature using the realm's JWKS, enforce issuer, audience, and expiration, and check roles before granting access. Discovery is at `http://localhost:8081/realms/contacts/.well-known/openid-configuration`.

To save a token to a new file readable only by your user:

```bash
python3 identity/scripts/get_token.py reporter --save /tmp/contacts-reporter.jwt
```

A future reporter can send it using `Authorization: Bearer <access_token>`. The API now validates bearer JWTs and enforces roles and subject-based contact ownership. See [API security](../docs/security.md). The reporter can access only contacts owned by its own service-account subject.

## Reproduce and export the configuration

The tracked `realm/contacts-realm.json` is an importable, export-format realm configuration. All user passwords and the reporter secret use environment-variable placeholders, which Keycloak resolves during import. It contains demonstration identities only, no production users, password hashes, signing keys, or real secrets.

On a fresh environment, copy the repository, create local `.env` credentials, and run Compose. Startup import skips a realm that already exists; changing JSON or `.env` does not update an existing realm or rotate its credentials. Make changes in the admin console for an existing environment. For a fresh local import without deleting the old data, use a new Compose project name (for example, `docker compose -p contacts-fresh up -d`; stop the old server first to free port 8081).

To capture settings from a running local realm, stop the server before offline export:

```bash
mkdir -p identity/exports
docker compose stop keycloak
docker compose run --rm --no-deps --volume "$(pwd)/identity/exports:/opt/keycloak/data/export" keycloak export --realm contacts --file /opt/keycloak/data/export/raw-realm.json --users skip
python3 identity/scripts/sanitize_export.py identity/exports/raw-realm.json identity/exports/contacts-realm.json
docker compose up -d keycloak
```

Raw exports can contain client secrets and key-provider data even when users are skipped. `identity/exports/` is ignored by Git. The sanitizer keeps a limited set of realm and application-client settings, replaces users and all credentials with the reviewed template, and retains the template's role scopes and mappers. It intentionally does not migrate arbitrary live users, custom providers, custom mappers, or new roles. Review the sanitized file before sharing; update the tracked template explicitly when those features are needed.

Validate the checked-in configuration and scripts:

```bash
python3 -m unittest discover -s identity/scripts -p 'test_*.py'
```

After Docker is available, also run both token commands and confirm the claims match the table. Automated static checks do not replace testing a live realm import and token issuance.

Check discovery and the reporter token automatically after the server starts:

```bash
python3 identity/scripts/check_identity.py
```

This checks the reporter's subject, issuer, API audience, expiration, client identity, and `user` role without `admin`. It prints neither credentials nor bearer tokens and does not perform cryptographic JWT verification. `KC_HOSTNAME` pins the development issuer to `http://localhost:8081`; change the hostname and script issuer together when deploying elsewhere.

References: [Keycloak Docker setup](https://www.keycloak.org/getting-started/getting-started-docker), [realm import/export and environment placeholders](https://www.keycloak.org/server/importExport), and [service accounts and role scopes](https://www.keycloak.org/docs/26.8.0/server_admin/#_service_accounts).
