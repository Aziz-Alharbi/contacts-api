# Observability

API and Reporter use the shared Micrometer Prometheus registry, their existing SmallRye Health checks, JSON console logging and Quarkus OpenTelemetry. Only Prometheus, Grafana and Jaeger are optional tools, all outside Kubernetes. No collector, logging platform or operator is required. The existing two-replica API/Compose dependency topology is unchanged.

## Application signals

| Signal | Behavior |
|---|---|
| `/q/metrics` | Prometheus format; default JVM and HTTP metrics are reused |
| `contacts_events_total` | Counter with only `operation`, `broker`, `result` labels; no event/contact/correlation identifiers |
| `contacts_outbox_pending` | API database snapshot every 10 seconds, tagged only by broker |
| `/q/health/live` | Existing liveness; no new broker-dependent liveness check |
| `/q/health/ready` | Existing database/broker readiness and Reporter Kafka consumer readiness |
| JSON logs | Event/correlation IDs in MDC; worker scopes restore previous MDC/context in `finally` through try-with-resources |
| Tracing | Disabled by default; enable SDK/export only while Jaeger is available |

Counter operations are `publish`, `process`, and `poll`; results include `success`, `failure`, `duplicate`, and `dead_letter`. RabbitMQ failed handler attempts increment failure; a successful ACK increments success. Kafka success counts processor execution before its enclosing transaction commits. Outbox publication counters count acknowledged attempts, not unique committed business events; a later transaction rollback or replay can increase them again. They are operational counters, not an exactly-once accounting ledger. Counters restart with each JVM.

Outbox gauges are cached snapshots of the shared database on each replica; aggregate with `max`, not `sum`, across API replicas. Initial values are zero until the first successful refresh. Metric labels never contain an ID, email, exception message, token or arbitrary user input.

The HTTP request's W3C `traceparent` is saved as one nullable `VARCHAR(55)` column in the existing Outbox through additive Flyway migration `V5__retain_outbox_trace_context.sql`. Publishing starts a producer span with that saved parent and puts the producer's `traceparent` in RabbitMQ/Kafka headers. Consumers extract it explicitly on scheduler/Streams threads and create consumer spans. Old Outbox rows/messages without this metadata still work and start a new trace. `ContactCreatedEvent.v1` and its payload are unchanged. Baggage, cookies, authorization headers and vendor tracestate are not copied. Span attributes include event/correlation IDs for diagnostics, never metric labels. Exceptions are marked as span errors without recording exception bodies. Kafka fail-closed exceptions retain only the failure class, not a potentially sensitive invalid payload. API raw Hibernate SQL-error and transaction-cause categories are disabled because constraint errors can contain persisted field values; HTTP status metrics, readiness and sanitized event failure logs remain available.

Unit tests establish parent/context and header propagation. The local deployment verification below also establishes one connected trace in Jaeger across a real authenticated request, Outbox dispatch and both consumers. This is local functional evidence, not a production load or durability test.

## Build and deployment

Both images were built and deployed locally with the existing Java 21 Dockerfiles and UID/GID 10001: `contacts-api:observability-20261022` and `contacts-reporter:observability-20261022`. Flyway applied the nullable trace column normally. The simplified topology and existing dependency containers/volumes were retained.

The deployment was explicitly approved after a successful isolated backup restore. Preserve the existing private runtime environment, volumes and Kubernetes rollback snapshots from [the Kubernetes guide](kubernetes.md). Do not regenerate credentials or recreate dependency containers for Observability.

After approval, build sequentially to limit memory (repository root):

```bash
docker build -f contacts-api/src/main/docker/Dockerfile.jvm -t contacts-api:observability-20261022 .
docker build -f contacts-reporter/src/main/docker/Dockerfile.jvm -t contacts-reporter:observability-20261022 .
minikube -p contacts-local image load contacts-api:observability-20261022
kubectl --context contacts-local apply -f k8s/
kubectl --context contacts-local apply -f observability/kubernetes-discovery.yaml
kubectl --context contacts-local -n contacts-local rollout status deployment/contacts-api --timeout=180s
```

Do not reuse an old image tag for changed code. The Dockerfiles still build/run Java 21 as non-root. CI/CD and Coverage thresholds are unchanged. Rolling back to the previous binary does not require dropping the nullable column; do not undo it destructively.

## Optional tools, one at a time

Use the existing shell-local Compose project/private credentials from `docs/kubernetes.md`; none belong in this repository. The tools overlay is used together with the two existing Compose files. Starting a named tool with `--no-deps` does not recreate applications:

```bash
docker compose -f compose.yaml -f compose.kubernetes.yaml -f compose.observability.yaml config --quiet
docker compose -f compose.yaml -f compose.kubernetes.yaml -f compose.observability.yaml --profile tracing up -d --no-deps jaeger
```

Jaeger UI: [localhost:16686](http://localhost:16686). OTLP gRPC is reachable internally at `http://192.168.49.20:4317` without publishing a collector port to the host. Check that .20 is free first. Jaeger uses bounded in-memory trace storage by default; traces are transient. The service has a 256Mi container limit and a 160Mi Go memory target. Do not run it when Docker lacks headroom.

After deployment approval and Jaeger startup, enable tracing in the new application images:

```bash
kubectl --context contacts-local -n contacts-local set env deployment/contacts-api OTEL_SDK_DISABLED=false OTEL_EXPORTER_OTLP_ENDPOINT=http://192.168.49.20:4317
CONTACTS_TRACING_DISABLED=false docker compose -f compose.yaml -f compose.kubernetes.yaml -f compose.observability.yaml up -d --no-deps --no-build contacts-reporter
```

These commands roll/recreate application containers, so they are not read-only checks. Reporter keeps its existing state and credential volumes. Sampling is parent-based/always-on for this bounded local demonstration; default SDK-disabled mode avoids exporter retries when tracing is unused. OTLP metrics/log export is disabled to avoid duplicating Prometheus metrics or JSON console logging. Framework health/metrics URIs are suppressed from tracing.

Create one authenticated Contact as described in the Kubernetes guide. Save only the resulting IDs and trace IDs, not tokens. Compare `mdc.correlationId`, `mdc.eventId` and `mdc.traceId` in JSON logs. In Jaeger select `contacts-api` / `contacts-reporter`, search the trace ID or `contacts.event_id` tag, and confirm HTTP → persisted Outbox parent → producer → consumer spans. If the exporter, context or consumer is absent, record that limitation rather than claiming continuity.

Disable tracing and stop Jaeger before starting the metrics tools if resources are tight:

```bash
kubectl --context contacts-local -n contacts-local set env deployment/contacts-api OTEL_SDK_DISABLED=true
CONTACTS_TRACING_DISABLED=true docker compose -f compose.yaml -f compose.kubernetes.yaml -f compose.observability.yaml up -d --no-deps --no-build contacts-reporter
docker compose -f compose.yaml -f compose.kubernetes.yaml -f compose.observability.yaml stop jaeger
```

Prometheus can then be started alone, followed by Grafana only if measured resources permit:

```bash
docker compose -f compose.yaml -f compose.kubernetes.yaml -f compose.observability.yaml --profile metrics up -d --no-deps prometheus
docker exec contacts-local curl --fail http://127.0.0.1:9090/-/ready
docker exec contacts-local curl --fail http://127.0.0.1:9090/api/v1/targets
docker compose -f compose.yaml -f compose.kubernetes.yaml -f compose.observability.yaml --profile dashboard up -d --no-deps grafana
```

Prometheus shares only the existing node's network namespace (`container:contacts-local`). A private read-only `/etc/resolv.conf` mount inside the Prometheus container uses the existing cluster DNS `10.96.0.10`; it does not edit the node's resolver. One optional headless Service, `contacts-api-metrics`, selects the existing API Pods without adding a workload. DNS discovery refreshes their ready addresses every 15 seconds, so each replica has a separate target and counter series. The normal API ClusterIP Service remains unchanged. This needs no Kubernetes API credentials, RBAC, tunnel, NodePort or refresh script.

Prometheus remains a separate 192Mi Docker container, not a Kubernetes workload. Its six-hour/128MB retention bounds local storage. Reporter is scraped at `192.168.49.14:8082`. Check the cluster DNS IP and Docker network addresses if this local environment changes. Host API metrics are [localhost:18080/q/metrics](http://localhost:18080/q/metrics) while the existing access forward runs; Reporter metrics are [localhost:8082/q/metrics](http://localhost:8082/q/metrics). Prometheus does not depend on that forward. Its target API is inspected from the node at `http://127.0.0.1:9090/api/v1/targets`; after the approved rollout it must show two API targets and one Reporter target, all healthy. A successful DNS probe against the existing normal API Service proves the resolver path, but discovery of both Pods through the new headless Service remains unverified until that Service is applied.

Grafana: [localhost:3000](http://localhost:3000), one provisioned `Contacts overview` dashboard. The datasource UID is fixed and the dashboard/provider files are repeatable and read-only. The UI is loopback-bound, with anonymous Viewer access and login form disabled. A random internal admin password is generated without embedding a default password in source. Grafana has a 192Mi limit and its own persistent volume; application secrets are not mounted into any tool. Grafana connects to Prometheus at `http://contacts-local:9090` through the existing Docker network.

Stop only tools, keeping their volumes and the prepared headless Service:

```bash
docker compose -f compose.yaml -f compose.kubernetes.yaml -f compose.observability.yaml stop grafana prometheus jaeger
```

Never use `down -v`, pruning, cluster deletion or a database reset. Stop testing on OOM, severe pressure or administrative timeouts. Inspect `docker stats --no-stream`, free disk and node memory pressure before each extra tool. There is no automatic restart policy or dependency forcing all tools to start together.

## Earlier build and isolated verification evidence

| Check | Actual result |
|---|---|
| Maven/Coverage | Host offline verify passed: 241 cases, 238 executed successfully, three existing opt-in broker tests skipped, zero failures/errors; existing scoped Coverage gates passed unchanged |
| Host JDK | Maven used JDK 23.0.2 with compiler `release=21`; not a JDK 21 test run |
| Java 21 full-suite attempt | First needed a public Linux-native dependency, then its isolated 1Gi cgroup killed the forked VM (exit 137, OOMKilled); not a pass; no limits increased |
| Application images | Both built with the existing Java 21 Dockerfiles and non-root UID/GID 10001; not deployed |

API image ID: `sha256:7730af007e47630162d873669ee53e119062dcec8f3e2a26e8e9dc4cdc232a78`. Reporter image ID: `sha256:d11ecc9d926368e4f2756bc0b55f8713cbfac575a173e61f410c3ccbe8fc7351`.

| Continued checks | Actual result |
|---|---|
| HTTP endpoints | New Quarkus fixture instances returned 200 for metrics/live/ready in both modules, exposing HTTP and custom event metrics |
| Java 21 image runtime | Network-isolated Reporter image returned 200 for metrics/live/ready with messaging/Kafka/OIDC client disabled; about 112MiB under a 192Mi limit; stopped afterward |
| Context/logging | Tests cover Outbox parent capture, both publisher headers, trace identity and MDC restoration; parsed JSON event logs include correlation/event MDC |
| Sensitive failure data | Rejected Kafka payload test verifies the marker is absent from the exception chain; raw SQL/transaction cause logging is disabled to avoid persisted values in logs |
| Prometheus syntax | `promtool check config` passed in an isolated 128Mi, network-none validation container |
| Prometheus DNS | Actual `promtool check service-discovery` against the existing normal API Service discovered `10.97.23.75:8080` from the node network with the private resolver mount; no node resolver changes |
| Discovery manifest | Server dry-run passed; new headless Service not created; two-Pod discovery not yet exercised |
| Grafana | Dashboard JSON and provisioning datasource/path references passed static validation; running UI/provisioning not tested |
| Jaeger | Not started; no Jaeger trace or connected end-to-end trace observed |
| Live rollout | Pending approval: new images, additive Flyway column, headless Service and staged tools |

A BusyBox `nslookup` attempt returned no answer and is not treated as a successful DNS check; the successful evidence is Prometheus's own discovery engine. The existing node resolver stayed unchanged. Once the approved headless Service exists, its two ready Pod addresses must be verified as distinct healthy Prometheus targets before claiming per-replica collection.

An OpenTelemetry API warning was diagnosed, not hidden: Quarkus's automatic Prometheus exemplar sampler calls `Span.fromContextOrNull` with a null context while the SDK is disabled. The detailed stack identifies `OpentelemetryExemplarSamplerProvider`; endpoint and metric tests pass. Exemplars are not used by this dashboard. Recheck this framework integration during the tracing rollout; no dependency versions or log filters were changed to conceal it.

Final pre-rollout resource samples were about 1.39GiB for the unchanged 2.93GiB Minikube container and about 105–160MiB for current Reporter, with node pressure avg10/avg60 at zero. About 16GiB host disk remained. No simultaneous three-tool footprint was measured. Tests/validation containers are retained stopped; no application data, broker messages, Kubernetes resources, volumes or images were deleted. The live API remains 2/2 on its previous image. No Git/GitHub operation occurred.

Local evidence (temporary, not durable):

- `/private/tmp/contacts-observability-verify.log`: successful host Maven/Coverage and JSON logs.
- `/private/tmp/contacts-observability-java21-verify.log`: failed resource-limited full Java 21 attempt.
- `/private/tmp/contacts-observability-api-image.log`, `/private/tmp/contacts-observability-reporter-image.log`: Java 21 image builds.
- `/private/tmp/contacts-observability-reporter-fixture.log`: isolated new-image startup logs.
- `/private/tmp/contacts-observability-otel-diagnostic.log`: framework warning diagnosis.

## Changed files

- API/Reporter POMs and properties: JSON logging; shared Events POM: Micrometer/OpenTelemetry dependencies.
- Shared `EventTelemetry`, Rabbit/Kafka publishers: bounded counters, scoped MDC and W3C headers.
- API correlation filter, Outbox entity/record/publisher, new `OutboxMetrics` and additive V5 migration.
- Reporter Rabbit consumer, Kafka topology/startup, and focused tests. Existing ACK/NACK, ordering, retry and deduplication behavior is retained.
- Optional Compose tools, Prometheus config/private resolver, optional headless discovery Service, and Grafana datasource/provider/dashboard.
- Future image references in simplified Kubernetes/Compose manifests; README, this guide and Kubernetes image notes.

The earlier tables above describe pre-deployment checks and limitations; the following live evidence supersedes their pending-deployment, discovery, Grafana and Jaeger entries. CI/CD and Coverage rules were unchanged.

## Live deployment verification — 2026-10-22

The existing Compose project is `contacts-clean-final-20261020`. All Compose commands against this environment must retain `--env-file /private/tmp/contacts-compose-transition-20261022/runtime.env -p contacts-clean-final-20261020` before the three `-f` arguments. The private environment contains the matching existing credentials; do not substitute another environment or print it. Current Kubernetes commands additionally require `--server=https://127.0.0.1:50267 --request-timeout=10s` because the stored context endpoint is stale. Verify that address before future runs.

Before mutation, current Kubernetes objects and container metadata were saved privately under `/private/tmp/contacts-observability-rollout-20261022/` (restricted directory/files). `contacts.sql` was produced using a single-transaction logical backup. It was restored into a separate network-isolated, 512Mi MySQL validation container with no existing mounts or published ports. The restored database contained two Contacts, four successful migrations and zero pending Outbox rows; `CHECK TABLE` returned OK for Contacts, Outbox and Flyway history. The validation container was stopped and retained. Backup SHA-256: `3fca469497a3d79120daee51a6cc52360df82d66a807be37e9dffee7757b7a16`. These temporary files contain private data and must not be committed; this is not a remote/durable backup.

Migration `V5__retain_outbox_trace_context.sql` only adds nullable `traceParent VARCHAR(55)`; it has no UPDATE, DELETE or table recreation. Flyway recorded versions 1–5 successful during ordinary API startup. Both original Contacts remained, and the two authenticated test creations brought the final count to four. Pending Outbox rows returned to zero after normal publication.

The host `minikube` executable was unavailable. The first attempted import through the node's Docker client failed because the node uses containerd; no daemon or node was restarted. The existing image was successfully imported using `docker save contacts-api:observability-20261022 | docker exec -i contacts-local ctr -n k8s.io images import -`. A brief ImagePullBackOff on the surge Pod cleared after import, while the old two replicas remained available. No image download or replacement cluster was needed. Application rolls also required reopening the existing host access port-forward when its selected Pod was replaced; Prometheus never uses that forward.

| Live check | Observed result |
|---|---|
| API deployment | Two ready replicas on the fixed Observability tag; final Pods `contacts-api-f6c8d77c5-z4bd8` and `contacts-api-f6c8d77c5-4cv2l`, zero restarts |
| Health/Metrics | Both initial updated Pod IPs and Reporter returned HTTP 200 for `/q/health/live`, `/q/health/ready`, `/q/metrics`; service access returned all three 200 again after tracing rollout |
| Authentication/data | Keycloak PKCE token acquired with expected issuer, anonymous API returned 401, authenticated creates returned 201 (Contact IDs 3 and 4); no token printed |
| Messaging | Reporter JSON logs prove natural successful RabbitMQ ACK and Kafka processing for each created event, with matching event/correlation IDs; no manual broker consumption, purge or deletion |
| Discovery/Prometheus | Headless Service applied; distinct targets `10.244.0.17:8080` and `10.244.0.18:8080`, plus Reporter `192.168.49.14:8082`, all `up` with empty scrape errors |
| Metrics data | Prometheus returned four success counter series: API publication to both brokers and Reporter processing from both brokers. API exposed HTTP counters for 401/201 and zero pending gauges for both brokers. Reporter exposed its HTTP 200 counter after `/reports/contacts/count` succeeded using its service account; its scoped count was zero, not a global database total |
| Grafana | `/api/health` reported database OK; provisioned `contacts-overview` dashboard loaded with four panels; its actual datasource proxy returned success and four event metric series. Browser rendering/screenshots were not checked |
| Jaeger | API returned both application service names and a connected five-span trace with HTTP request, both publishers and both consumers; parent relationships verified, not inferred solely from log IDs |
| Stability | No observed application or tool OOM/restart; Kubernetes Ready=True and MemoryPressure=False during checks; existing memory caps unchanged |

Trace evidence: Contact 4, event `cc8e3e69-ba2b-45ca-b6e4-a0aaeb203b58`, correlation `f8bd1127-6ddb-4209-969c-004d3a274d41`, trace `1f38ceea31bb01be33aeb6d6565a7917`. The HTTP span `e00de33f3b6fdfdc` is the parent of RabbitMQ publisher `b9f6622a5d2df60d` and Kafka publisher `843cb5e2b30be575`; the matching Reporter consumer spans reference those producer IDs. All five spans share that trace ID. The trace response was saved privately as `trace.json` beside the backup. [Open the local trace](http://localhost:16686/trace/1f38ceea31bb01be33aeb6d6565a7917) while Jaeger remains running; its in-memory storage is transient.

Tools were staged: Prometheus first, then Grafana; both stopped with volumes preserved before Jaeger was started. Prometheus used about 21–31MiB, Grafana about 122MiB at startup, Jaeger about 54MiB during verification. Final Docker samples: node 1.35GiB/2.93GiB, Reporter 219MiB, Kafka 214MiB, Keycloak 218MiB, MySQL 76MiB, RabbitMQ 36MiB, Jaeger 54MiB (about 2.15GiB summed working set; not a complete Docker VM memory measurement). macOS reported 39% free memory and about 13GiB free disk. Memory-pressure avg10 briefly reached about 1.07 during Grafana startup and fell to 0.04 at the final sample. No all-three-tool or sustained-load test was performed.

Final state: API 2/2 and Compose dependencies/Reporter running, tracing enabled for review, Jaeger running; Prometheus and Grafana stopped. Their tested targets were the pre-tracing-roll Pod addresses; discovery after that second roll was not re-tested. Restart metrics tools only with adequate headroom. To disable tracing, use the commands above with the explicit existing project/private environment, wait for API 2/2, reopen the access forward if necessary, then stop Jaeger. Do not stop Jaeger while leaving exporters enabled unless accepting exporter retry logs.

Rollback without data loss: extract only the original `contacts-api` Deployment from `kubernetes-before.json`, remove server-managed metadata/status and apply it, waiting for two ready replicas. This restores the previous image and environment. Restore Reporter to `contacts-reporter:minikube-20261020` by setting `CONTACTS_REPORTER_OBSERVABILITY_IMAGE=contacts-reporter:minikube-20261020 CONTACTS_TRACING_DISABLED=true` on the existing three-file Compose `up -d --no-deps --no-build contacts-reporter` command with the same private environment/project. Preserve every existing mount and dependency container. Stop optional tools only; retain the headless Service and additive nullable column. Do not restore the backup over newer writes or drop the column. Reopen the host access forward if needed. The binary rollback plan is documented but was not exercised because deployment succeeded.

No source logic, CI/CD or Coverage changes were needed during this rollout; only this guide and README were updated. Maven was not rerun during deployment: the earlier successful host verification and its JDK/Java-21 resource limitation remain as reported above. No Git/GitHub operation, data deletion, volume/image removal, cluster restart or system/memory setting change occurred.

References: [Quarkus Micrometer](https://quarkus.io/guides/telemetry-micrometer/), [JSON logging](https://quarkus.io/version/3.40/guides/logging/), [OpenTelemetry](https://quarkus.io/version/3.40/guides/opentelemetry/), [Prometheus](https://prometheus.io/docs/prometheus/latest/configuration/configuration/), [Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/), [Jaeger](https://www.jaegertracing.io/docs/2.10/getting-started/).
