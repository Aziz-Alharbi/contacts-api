# Java identity and verification commands

All repository utilities are Java 21 commands hosted by the existing Contacts Reporter module. There is no additional Maven module or HTTP endpoint. The named Quarkus entry point is selected only in the `tools` profile; normal Reporter startup still runs the reporting service. Tool mode disables the HTTP listener and automatic OIDC client setup, so initializing credentials and inspecting Git need no external service or preexisting secret.

Build the tools from the repository root:

```bash
mvn -pl contacts-reporter package -Dquarkus.profile=tools -DskipTests
java -Dquarkus.profile=tools -jar contacts-reporter/target/tools-app/quarkus-run.jar --help
```

The command package is placed in `target/tools-app`, separately from the normal Reporter package in `target/quarkus-app`. Keep the whole directory beside its runner JAR. Run commands from the repository root or provide `--root /absolute/path/to/repository`.

| Command | Function |
|---|---|
| `init-env` | Create a new `.env` containing independently generated identity credentials |
| `token demo-user` | Device Authorization Flow; sign in as demo-user in the Keycloak browser page |
| `token demo-admin` | Device Authorization Flow; sign in as demo-admin in the Keycloak browser page |
| `token reporter` | Client Credentials using the configured service-account secret |
| `check-identity` | Check discovery and decoded reporter identity/audience/expiration/roles |
| `sanitize-export RAW_JSON NEW_JSON` | Export reviewed configuration with demo credential placeholders |

Example human login and private token save:

```bash
java -Dquarkus.profile=tools -jar contacts-reporter/target/tools-app/quarkus-run.jar token demo-user --save /tmp/contacts-user.jwt
java -Dquarkus.profile=tools -jar contacts-reporter/target/tools-app/quarkus-run.jar token demo-admin --save /tmp/contacts-admin.jwt
```

Open the displayed verification URL, enter the one-time code, and sign in to the intended account. Account arguments guide the browser login; Keycloak chooses the identity actually authenticated, including any existing browser session. Check `sub` and `roles` in the printed claims. The command uses S256 PKCE, handles `authorization_pending` and `slow_down`, and stops when permission is denied or the device code expires. `user` and `admin` remain aliases. No user password is submitted or read by this flow.

The tool prints only `sub`, `iss`, `aud`, `exp`, and `roles`. It never prints bearer tokens or reporter secrets. `--save` creates a new file with POSIX mode 0600 and refuses an existing file or symbolic link. Keep token files outside Git. Decoding claims is inspection, not cryptographic verification; Contacts API continues to verify signature, issuer, audience, and expiration.

Reporter secrets come from environment variables or the ignored root `.env`; environment values take precedence. `KEYCLOAK_URL` changes the default issuer base, or use `--issuer https://host/realms/contacts`. Remote identity servers require HTTPS. No credential is accepted as a command-line option.

Run the Java tests without external services:

```bash
mvn -pl contacts-reporter -Dtest='com.unifonic.tools.*Test' test
mvn clean verify
```

Device-flow HTTP fixtures use a simulated clock and wait function, so tests never sleep. Identity configuration, sanitizer, JWT inspection, and private file handling are covered by JUnit. These tests do not replace a manual browser demonstration against a live realm.

For manual real-service verification, secret history review, and controlled defect demonstrations, follow [security verification](../docs/security-verification.md) and [testing and coverage](../docs/testing-and-coverage.md). These are review procedures outside the runtime command package. Pattern scanning cannot prove the absence of unknown secrets or inspect objects missing from this local clone.

References: [Quarkus command mode](https://quarkus.io/guides/command-mode-reference/) and [Keycloak OIDC endpoints](https://www.keycloak.org/securing-apps/oidc-layers).
