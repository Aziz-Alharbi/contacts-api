# Continuous integration and image publishing

## Execution paths

`.github/workflows/ci.yml` runs on pull requests targeting `main`, including updates and reopened requests. It is also called by `.github/workflows/publish.yml` for pushes to `main`. There are no path filters: changes anywhere in the repository are checked. Fork requests use `pull_request`, never `pull_request_target`.

Validation uses Ubuntu 24.04, Temurin Java 21 and the runner's Maven. `setup-java` caches Maven dependencies using all reactor POMs and the public Maven settings file. The root command is:

```bash
mvn --batch-mode --no-transfer-progress \
  --settings containers/maven-settings.xml -itr clean verify \
  -DskipTests=false -Dmaven.test.skip=false \
  -Dmaven.test.failure.ignore=false \
  -Djacoco.skip=false -Dmaven.antrun.skip=false
```

All three modules build together. Test failures fail the job; there is no ignored failure or fallback success. Existing tests and coverage configuration remain unchanged. Maven deletes stale coverage data and requires fresh execution data during verification.

The default suite uses H2 and local HTTP/JWKS fixtures. It requires no production secrets, MySQL, Keycloak or brokers. Three existing real-broker tests remain conditional on `kafka.integration` or `rabbitmq.integration`; they are skipped in this workflow, as in the default local suite. Native tests remain outside the default JVM configuration. See [broker verification](kafka.md#tests) and [container verification](containers.md#end-to-end-verification) for separate live checks. Building images does not prove that the entire Compose system was exercised.

## Coverage and reports

`verify` preserves the existing **80% line coverage minimum for every included class**. The included paths are:

- API: `com/unifonic/contacts/service/**/*.class` and `com/unifonic/contacts/resource/**/*.class`.
- Reporter: `com/unifonic/reporter/ReportService*.class` and `com/unifonic/reporter/ReportResource.class`.

This is a scoped business/endpoint gate. Entities, DTOs, repositories, exception handling, messaging/outbox/Kafka infrastructure, readiness helpers, identity utilities and `contacts-events` are not comprehensively measured by these reports. Neither the gate nor its reports establish whole-project coverage. No minimums were lowered and no exclusions were added.

An `always()` step uploads `verification-reports-<commit-sha>` for 14 days. It includes both modules' Surefire reports, API Failsafe reports when present, and JaCoCo HTML/XML reports. Test failures can stop Maven before the report goal or before the next module runs; only generated files can be uploaded. Missing reports produce a warning rather than masking the original failure. Cancellation or runner failure can prevent artifact upload.

## Container builds

After verification, separate matrix jobs build `contacts-api` and `contacts-reporter` using their existing `src/main/docker/Dockerfile.jvm` files, with the repository root as context. Both must succeed. Images target `linux/amd64`; cross-platform execution is not claimed.

Buildx uses a separate GitHub Actions layer-cache scope for each application. This cache does not guarantee persistence of the Dockerfile's Maven cache mount. Cold builds still depend on public Maven repositories. The host Maven cache is separate from Docker's cache.

The existing multi-stage images copy only the Quarkus runtime into the final stage. `.dockerignore` excludes local environment files, keys, Git metadata and host build outputs. No credentials are passed as build arguments or copied from a developer's Maven settings. The runtime check requires UID/GID `10001:10001`, the expected revision label, no source/build/Maven directories at the checked paths, and no credential files in the application/helper directories. The generated development credentials directory must be empty before runtime mounts. These checks supplement review; they are not a general secret scanner.

The Dockerfiles skip tests during packaging. This cannot replace the separate required verification job: image jobs depend on its success. Pull requests never log into GHCR, push images or export release image archives.

## Publishing

`publish.yml` runs only on `push` to `main`. It calls the same CI workflow and rechecks the actual pushed commit, including merge commits. Both images are built and checked before the publishing job can start. That run exports the images as one-day artifacts; the publishing job downloads only those named artifacts from its own run, loads both, and verifies their revision and non-root configuration before authenticating.

The publishing job pushes those exact images without rebuilding them:

```text
ghcr.io/<lowercase-owner>/contacts-api:sha-<full-commit-sha>
ghcr.io/<lowercase-owner>/contacts-reporter:sha-<full-commit-sha>
```

Each image has `org.opencontainers.image.revision` and `org.opencontainers.image.source` labels. The run summary records successfully published references. There is no `latest` tag, so concurrent or delayed builds cannot move that tag backwards. SHA tags identify the source commit; they are not registry-enforced immutable references. Record the registry digest when deploying for immutable selection.

Publishing uses a concurrency group without canceling an active publication. GitHub may replace a pending run with a newer pending run; this is not a FIFO release queue. Every run that does publish still validates its own commit.

GHCR does not provide an atomic transaction across two image repositories. If an upload fails after the other succeeds, the workflow is red and one SHA tag can exist. Do not treat an incomplete run as a complete release. Fix registry access or availability and rerun the failed publishing job while its one-day artifacts remain available, or rerun the whole workflow. A failed validation or image build prevents the publishing job entirely.

## Permissions and repository setup

Default permissions are `contents: read`; checkout does not persist credentials. Only the publishing job receives `packages: write`. Its login uses GitHub's automatically generated `GITHUB_TOKEN`; no PAT, custom secret, production password, OIDC token permission or repository write permission is required. Actions are pinned to full commit SHAs. The publishing job does not check out or execute application source. Tokens are passed directly to the login action and are not printed.

The repository owner must enable Actions and allow the pinned `actions/*` and `docker/*` actions. Organization policy must allow package publication. Existing GHCR packages with these names must grant this repository Actions write access; unrelated packages under the same owner can otherwise cause permission errors. Review package visibility separately; publishing does not promise public access.

Configure branch protection/rulesets to require the successful CI verification job and **both** image matrix jobs before merging. Select the exact check names shown by the first real run. A red workflow alone does not prevent an administrator from merging without required checks. Protect workflow changes through normal review. This implementation does not change repository settings or create a branch, commit, push, pull request or merge.

See the official references for [Maven validation](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven), [Buildx builds](https://github.com/docker/build-push-action), [artifacts](https://docs.github.com/en/actions/tutorials/store-and-share-data), and [GHCR access](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

## Verification procedure

The owner performs all repository operations. Keep any negative demonstration on a disposable pull request targeting `main`; do not merge the intentionally failing change.

1. Open a normal pull request containing the workflows and valid application code. Record its head/merge SHA and Actions run URL. Require successful verification and both image builds. Download the report artifact and confirm that no publishing job or registry login ran for the pull request.
2. In the disposable pull request only, add a plain JUnit test under the API test sources containing `@Test void rejectsInvalidBuild() { org.junit.jupiter.api.Assertions.fail("controlled verification failure"); }`. Do not change application code or test-skipping flags. Update the request and require an actual assertion failure, a red verification job, preserved Surefire reports and skipped image jobs. A compilation error is not evidence of test failure detection.
3. Remove that temporary test, update the request and require green checks again. Review the final diff to confirm that the deliberate failure is absent. Close the disposable request if it is only a demonstration.
4. To demonstrate the coverage check independently, use an isolated local copy and run the root verification command with `-pl contacts-api -am -Dtest=ContactGroupServiceTest -Dsurefire.failIfNoSpecifiedTests=false`. Require the chosen test to pass, followed by JaCoCo failure for unexercised included classes. Run the complete command again to restore full reports. Do not weaken coverage rules or commit a reduced-suite command to the workflow.
5. Merge the valid request only after the green checks. In the resulting `main` run require fresh verification, both image builds and then successful publication. Record the run URL, commit SHA, image references and both registry digests. For failure evidence, confirm the publishing dependency chain blocks publication when validation is red; never deliberately break `main` to demonstrate it.

Inspect a published image using the real lowercase owner and full SHA:

```bash
docker buildx imagetools inspect ghcr.io/<owner>/contacts-api:sha-<full-commit-sha>
docker pull ghcr.io/<owner>/contacts-api:sha-<full-commit-sha>
docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
  ghcr.io/<owner>/contacts-api:sha-<full-commit-sha>
docker image inspect --format '{{.Config.User}}' \
  ghcr.io/<owner>/contacts-api:sha-<full-commit-sha>
```

Repeat for `contacts-reporter`. The revision must match the pushed `main` commit and the configured user must be `10001:10001`. Private-image inspection requires appropriate registry access; do not paste credentials or tokens into evidence.

## Evidence status

Local verification and GitHub execution are distinct. The local Java 21/Linux default suite has passed with 231 successful tests and three conditional real-broker tests skipped. No GitHub Actions run, GHCR publication, pull request or merge has been performed from this workspace for these workflows.

Local checks on October 20, 2026:

- `actionlint` 1.7.12 accepted both workflows. Additional checks validated triggers, dependencies, least permissions, action SHA pins, report handling and Bash syntax.
- The scoped JaCoCo reports measured 142 covered API lines and 24 covered Reporter lines, with zero missed lines in that scope. These figures do not measure the whole project.
- A temporary JUnit assertion inside a separate Docker build stage produced one failed test and a Maven build failure. No failing test was added to the repository.
- Another isolated build stage ran ten passing `ContactGroupServiceTest` cases, then failed the coverage check for three other included classes below 80%.
- Both existing application Dockerfiles built successfully. The exact CI runtime-check script passed against both local images: user `10001:10001`, matching local verification label, and no prohibited files at the checked paths.

These builds used the existing dependency/build cache and the Docker host's Linux ARM64 platform. A cold-cache build, GitHub cache transport, artifact handoff between jobs, Linux AMD64 image builds and GHCR authentication/publication still require a real GitHub run. The negative-test changes exist only in temporary build stages; application source and the default test suite were not modified.

| Evidence | Status |
|---|---|
| Green pull request run URL and screenshot | Awaiting owner execution |
| Red assertion-failure run URL, screenshot and Surefire artifact | Awaiting owner execution |
| Restored green run URL | Awaiting owner execution |
| Successful `main` run URL | Awaiting owner execution |
| Both SHA-tagged image references and registry digests | Awaiting owner execution |

Replace pending evidence only with observed results. Local test/build output cannot substitute for these GitHub runs.
