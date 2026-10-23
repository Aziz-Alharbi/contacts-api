# Historical Kubernetes verification

This archive describes the previous all-in-cluster topology. Its manifests and scripts have been retired; commands below are historical evidence, not instructions for the current setup. Runtime successes recorded here do not validate the new Compose-backed topology. Use [the current guide](kubernetes.md) for operation.

# Kubernetes on Minikube

## Layout and scope

The manifests run in the `contacts-local` namespace and use a separate Minikube profile/context named `contacts-local`. They do not modify Compose, application Java code, Maven coverage rules or CI workflows.

`contacts-api` is a Deployment with two replicas and a ClusterIP Service on port 8080. Each Pod runs as UID/GID 10001, drops Linux capabilities, denies privilege escalation and does not mount a service-account token. Requests are 100m CPU/256Mi memory per API Pod; limits are one CPU/512Mi. Rolling updates keep existing ready replicas while starting one extra Pod.

The dependency manifests provide single-instance MySQL, RabbitMQ, Kafka and Keycloak StatefulSets with separate persistent claims, plus a single Reporter Deployment to exercise real consumption. This is a local demonstration, not a highly available database or broker installation. Two API replicas on one Minikube node do not provide protection against node failure.

| Location | Purpose |
|---|---|
| `k8s/namespace.yaml` | Namespace isolation |
| `k8s/deployment.yaml`, `k8s/service.yaml` | API replicas and routing |
| `k8s/configmap.yaml` | Non-sensitive connection and runtime settings |
| `k8s/dependencies/` | Services, stateful dependencies, PVCs and Reporter |
| `k8s/setup.sh` | Generate/preserve a Secret, import the realm ConfigMap and apply resources in readiness order |
| `k8s/check.sh` | Verify at least two ready API replicas and both health endpoints on each Pod |
| `k8s/recover.sh` | Replace exactly one API Pod and sample Service readiness during recovery |
| `k8s/liveness.sh` | Suspend Java in one Pod and require a container restart in the same Pod |
| `k8s/verification.yaml` | Opt-in end-to-end Java verification Job |

## Prerequisites

Install Docker Desktop, Minikube, kubectl, Bash and OpenSSL. Keep kubectl within one minor version of the Kubernetes server. The downloaded Minikube used during preparation was v1.39.0; kubectl was v1.36.1. Verify downloaded binaries against their official checksums.

The preparation binary is available locally at `/private/tmp/contacts-minikube`; it was not installed system-wide. Substitute that path for `minikube` in the commands below to retry with the already verified binary.

For the whole local stack, budget four CPUs, approximately 6GB for Minikube, at least 7GB available to Docker, and at least 20GB free disk space for images, layers and data. A host with 16GB RAM is preferable. The combined limits are larger than requests; monitor actual usage. Allow extra headroom for rolling updates and the verification Job. Do not resize Docker or delete user data automatically to meet these requirements.

Start a dedicated profile after checking resources and existing profiles:

```bash
minikube profile list
minikube -p contacts-local start --driver=docker --cpus=4 --memory=6000mb \
  --kubernetes-version=v1.37.0 --keep-context
kubectl --context contacts-local get nodes
```

All commands below explicitly target this context. Do not reuse an unrelated cluster or namespace. `--keep-context` leaves the previously selected context unchanged. The manifests rely on Minikube's default dynamic storage provisioner. Four dependency PVCs request 6Gi in total; these are independent of existing Compose volumes.

## Images

Build from the repository root using the existing Dockerfiles:

```bash
docker build -f contacts-api/src/main/docker/Dockerfile.jvm \
  -t contacts-api:minikube-20261020 .
docker build -f contacts-reporter/src/main/docker/Dockerfile.jvm \
  -t contacts-reporter:minikube-20261020 .
minikube -p contacts-local image load contacts-api:minikube-20261020
minikube -p contacts-local image load contacts-reporter:minikube-20261020
```

These fixed build tags are explicitly used by the API, Reporter and verification Job. They are local build identifiers, not a claim about a Git commit. Record their image IDs and the runtime Pod imageIDs in evidence. For subsequent source changes, choose a new tag and update all corresponding image references; `IfNotPresent` intentionally does not replace a cached image merely because a tag was reused.

```bash
docker image inspect --format '{{.Id}} {{.Architecture}} {{.Config.User}}' contacts-api:minikube-20261020
docker image inspect --format '{{.Id}} {{.Architecture}} {{.Config.User}}' contacts-reporter:minikube-20261020
```

Build/load images matching the Minikube node architecture. The local preparation host is ARM64. Images published by the CI workflow target AMD64; an AMD64-only GHCR image must not be assumed to run on an ARM64 cluster. A matching GHCR image can instead be referenced by SHA tag or registry digest, with a locally created imagePullSecret when private. Never put registry credentials in manifests or Dockerfiles.

Dependencies use the same versions as Compose: MySQL 8.4, RabbitMQ 4.1-management, Kafka 4.1.2 and Keycloak 26.4.0. If already available locally, load them to avoid another download:

```bash
minikube -p contacts-local image load mysql:8.4
minikube -p contacts-local image load rabbitmq:4.1-management
minikube -p contacts-local image load apache/kafka:4.1.2
minikube -p contacts-local image load quay.io/keycloak/keycloak:26.4.0
```

## Configuration and credentials

Run from the repository root:

```bash
bash k8s/setup.sh
bash k8s/check.sh
```

The setup script uses the explicit context, creates the namespace, and generates seven random values with OpenSSL directly into a Kubernetes Secret through a pipe. No credential files or literal values are added to the repository, image or command arguments. Do not enable shell tracing. It preserves an existing Secret rather than rotating passwords behind persisted databases. Do not remove or regenerate that Secret while retaining the dependency PVCs; database and imported realm credentials would diverge. Back up cluster credentials securely before changing the environment.

The Secret keys are `mysql-root`, `mysql-app`, `rabbitmq`, `keycloak-admin`, `demo-user`, `demo-admin` and `reporter`. Each application receives only its required secret keys through `secretKeyRef`. The existing entrypoints accept these environment variables, so API and Reporter do not need the Compose credential-file volume.

Kubernetes Secrets are not encrypted merely because values are Base64-encoded. Keep local kubeconfig access restricted and do not export Secret objects into source control or evidence. Production requires appropriate RBAC and encryption at rest/managed secret handling.

Setup creates `contacts-realm` from the existing sanitized realm JSON. It contains environment placeholders, not resolved passwords. The ConfigMap stores internal DNS addresses, topics and public issuer settings. Changing ConfigMap or Secret environment values requires a controlled rollout to reach running Pods.

## Networking and identity

API connects to `mysql:3306`, `rabbitmq:5672`, `kafka:19092` and `keycloak:8080` within the namespace. Kafka advertises `kafka:19092`, with a headless Service and stable controller address; no broker advertises the developer machine's localhost to Pods. API migrations remain enabled. Both replicas use the same MySQL database and the existing database-level outbox relay lock; message delivery still requires idempotent consumption when retries occur.

Keycloak's public URL is `http://localhost:18081`. Its realm issuer therefore matches API's explicit `QUARKUS_OIDC_TOKEN_ISSUER`. API retrieves JWKS from the internal Keycloak address with discovery disabled. Reporter similarly uses the internal token endpoint. To log in from the host and access API, keep separate terminals open:

```bash
kubectl --context contacts-local -n contacts-local port-forward service/keycloak 18081:8080
kubectl --context contacts-local -n contacts-local port-forward service/contacts-api 18080:8080
```

Then inspect `http://localhost:18080/q/health/ready` and `http://localhost:18080/q/health/live`. API endpoints remain protected; an anonymous `/api/v1/contacts` request must return 401. The imported public client keeps its existing localhost:8080 redirect URI; the Java verification Job uses that registered callback with PKCE without requiring a listening browser callback. Do not change realm security to password grants merely to simplify a check.

Port-forward chooses a Pod and may disconnect when that Pod is replaced. It is a developer access path, not evidence of Service load balancing or failover. The recovery script samples the actual ClusterIP Service from Reporter inside Kubernetes.

## Health and recovery

Kubernetes uses explicit HTTP probes on port 8080; Docker's image HEALTHCHECK is not automatically used by Kubernetes.

| Probe | Path | Timing |
|---|---|---|
| Startup | `/q/health/live` | Every 5s, 3s timeout, 60 failures allowed |
| Readiness | `/q/health/ready` | Every 15s, 10s timeout, 3 failures |
| Liveness | `/q/health/live` | Every 10s, 3s timeout, 3 failures |

Startup allows up to five minutes for JVM startup/migrations. Until it succeeds, other probes are held back. Readiness includes the existing database and broker health checks; a temporarily unavailable dependency removes an API Pod from ready Service endpoints after detection without using dependency health as a reason for liveness restart. Liveness checks JVM HTTP responsiveness. Readiness failures do not themselves restart a container.

Observe readiness and routing, including new Pods before they are ready:

```bash
kubectl --context contacts-local -n contacts-local get pods -l app.kubernetes.io/name=contacts-api -w
kubectl --context contacts-local -n contacts-local get endpointslices \
  -l kubernetes.io/service-name=contacts-api -o yaml
```

Only endpoints with `conditions.ready: true` are eligible for normal Service traffic. Capture the startup transition and EndpointSlice evidence. Requests already in flight can fail when a process stops; sampled success is not a zero-downtime guarantee.

Run the following tests only in the dedicated local environment after both API replicas and Reporter are ready. They intentionally interrupt one API replica and preserve dependency data:

```bash
bash k8s/recover.sh
bash k8s/liveness.sh
```

The first removes exactly one selected API Pod, samples Service readiness fifteen times during replacement, and requires two ready replicas again. Record old/new Pod names and UIDs. This tests Deployment replacement, not a liveness restart.

The second sends SIGSTOP to the Java process (PID 1) in one API Pod. It requires Restart Count to increase and the same Pod UID to become Ready again. It times out with failure if no restart is observed. Record kubelet events for the failed liveness probe as well; a rising count alone does not establish why a restart happened. The other API replica remains available in principle; verify actual EndpointSlice/Service observations rather than assuming uninterrupted service. Standard termination grace can add delay before SIGKILL of the suspended process.

## End-to-end verification

After setup/check succeed, create the opt-in Job:

```bash
kubectl --context contacts-local -n contacts-local apply -f k8s/verification.yaml
kubectl --context contacts-local -n contacts-local wait --for=condition=complete \
  job/contacts-verification --timeout=180s
kubectl --context contacts-local -n contacts-local logs job/contacts-verification
```

The Job reuses the existing Java `ComposeSmoke` entry point against Kubernetes DNS Services. It performs Keycloak authorization-code/PKCE login, checks issuer and authenticated API access, rejects anonymous access, creates a real Contact in MySQL through the API Service, and verifies a matching event on RabbitMQ and a transactional Kafka result with count one. It also checks Reporter's service-account isolation. It prints IDs and results, not passwords or bearer tokens. Confirm Reporter's RabbitMQ success log for the same event ID separately.

The Job is intentionally not part of setup and is not automatically deleted. To repeat without deleting an existing Job, copy its manifest to a temporary non-repository file, choose a new Job name, and apply that copy. A complete Job does not automatically rerun on apply. Each successful run creates a new test Contact; it does not reset or delete database records.

## Diagnostics and safe stopping

```bash
kubectl --context contacts-local -n contacts-local get deployments,statefulsets,pods,services,pvc
kubectl --context contacts-local -n contacts-local get events --sort-by=.lastTimestamp
kubectl --context contacts-local -n contacts-local describe pod <pod-name>
kubectl --context contacts-local -n contacts-local logs deployment/contacts-api --all-pods=true --tail=100
kubectl --context contacts-local -n contacts-local logs deployment/contacts-reporter --tail=100
kubectl --context contacts-local -n contacts-local logs <pod-name> --previous
minikube -p contacts-local status
```

Inspect ImagePullBackOff/image architecture, PVC binding, memory pressure/OOMKilled, database migrations, issuer/JWKS reachability, Kafka listeners and probe failures. Redact sensitive data before sharing logs. Do not print Secrets, process environments or access tokens as a diagnostic shortcut.

Stop port-forward processes with Ctrl-C, then use `minikube -p contacts-local stop` to stop the dedicated cluster while retaining resources and PVCs. This also retains the Secret required by existing database data. Do not delete the namespace, PVCs, profile, Docker volumes or images as a routine stop/reset operation. Existing Compose data is separate and is never mounted into this cluster.

## Observed results and limits

Preparation found an 8GB host, Docker with eight CPUs but only approximately 3.8GiB RAM, and approximately 9GiB free disk space. This is below the recommended headroom. An isolated Minikube start was attempted with four CPUs and 3000MB memory; no existing cluster or user data was removed.

The actual Minikube binary download completed and matched its official SHA-256. Downloading the 470MiB node image stalled through the default registry and the Docker Hub mirror; a direct Docker pull was also attempted. Cluster startup and application execution have not been demonstrated. A static configuration or successfully built application image is not a substitute for a ready Kubernetes cluster.

| Check | Observed status |
|---|---|
| Kubernetes YAML schema validation | 15 resources valid, zero invalid/errors using strict kubeconform against Kubernetes 1.36 schemas |
| Shell syntax | Setup, health and recovery scripts validated with Bash |
| API/Reporter images with the documented tags | Built locally using the existing Dockerfiles |
| Java 21/Linux Maven regression verification | Passed: 231 successful tests, three existing opt-in broker tests skipped, zero failures/errors |
| Existing JaCoCo gate | Passed without changing limits or scope |
| Existing CI workflow validation | Both workflows accepted by actionlint; their files were not changed |
| Kubernetes Secret creation and resource application | Not executed against a ready cluster |
| Ready API Pods/replicas | Not observed; desired replicas are two |
| Service routing and probes inside Kubernetes | Not executed |
| Authenticated Contact creation and dual-broker publication inside Kubernetes | Not executed |
| Pod replacement and liveness restart | Not executed |

The built ARM64 image references were recorded locally as:

```text
contacts-api:minikube-20261020
sha256:6538b83ac7f7b97c9a5d29ba5043c043eb77f8dc4da0d0552d6a88716e95ba99
contacts-reporter:minikube-20261020
sha256:e356ff74fc16ddccd934b589f04cba91908a9fbae880528c02c8492515a201b9
```

Both configurations report user `10001:10001`. These local inspection references do not imply a registry publication or a Kubernetes deployment. Download attempts were stopped after continued stalls and free host disk space fell to approximately 5GiB; downloaded data, existing images and volumes were retained. Final Minikube status reported that the profile's host does not exist, and no running Docker containers were observed.

The first fresh Maven attempt could not connect to Maven Central from Docker. Verification subsequently ran successfully in a Java 21/Linux Docker build with networking disabled and Maven offline, after restoring cached public dependencies and the missing Linux native artifact with its published checksum. This was a real test execution, not reuse of a cached test result. The scoped reports show 142 API lines and 24 Reporter lines covered, zero missed within the existing gate; they do not establish whole-project coverage. Local exported summaries and reports are under `/private/tmp/contacts-kubernetes-tests`, with the successful build log at `/private/tmp/contacts-kubernetes-tests-offline.log`.

Complete the setup, health, end-to-end and recovery commands above once registry access and resources permit, then append actual Pod UIDs, restart counts, EndpointSlice transitions, imageIDs and sanitized verification results. This delivery is not an assertion that the Kubernetes runtime acceptance checks have passed.

## Staged verification attempt

On October 21, 2026, a lighter execution was attempted with the existing Docker/Minikube memory limits. The intended first check used two API replicas and the four dependencies without concurrent Reporter or the full verification Job. Reporter consumption was to be checked separately afterward.

The cluster was already experiencing control-plane request failures. Attempts to persist `contacts-reporter` replicas at zero timed out from the host. The existing administrative client inside the node was also attempted, followed by a bounded direct Kubernetes scale-subresource PATCH using the existing client certificates through in-memory pipes. The direct request timed out as well. Credential values were not printed or added to repository files.

Graceful stop signals were sent only to positively identified Reporter Java processes, as authorized. A later Reporter PID was observed after the first process exited, so a lasting stopped state cannot be inferred from a delivered signal. The desired replica count of zero was not confirmed. Other application services were not deliberately stopped, and no API Pod was deleted for a recovery test.

Observed diagnostics included approximately 2.73GiB node memory consumption against a 2.93GiB limit, repeated cgroup memory-limit hits, substantial memory pressure and approximately four CPUs of utilization. No cgroup OOM kill was recorded. About 17GiB host disk space remained free. A node-local `/livez` request returned HTTP 200 once; this proves only that the health handler responded at that moment, not that authenticated storage-backed Kubernetes operations or workload routing were healthy. Timeouts continued for those operations. Memory pressure is an observed contributing constraint, not proof that it is the only cause of the control-plane failures.

| Requested check in the lighter execution | Actual result |
|---|---|
| Reporter confirmed stopped for the first check | Not established; scale requests timed out |
| Two ready API replicas and stable readiness/liveness | Not established in this attempt |
| Authenticated Contact creation | Not executed |
| Matching publication to RabbitMQ and Kafka | Not executed |
| Replacement of one API Pod with sampled Service availability | Not executed |
| Separate Reporter consumption check | Not executed |

The full verification Job was not launched. No new Contact/event test was represented as successful, and earlier image/Maven results do not substitute for these unexecuted checks. Docker/macOS memory settings, the Minikube memory limit, images, PVCs and persisted application data were not changed or deleted. Recovering the existing node by restarting it would temporarily interrupt the other services and requires separate authorization; it is not part of the Reporter-only stop permission. The Kubernetes acceptance checks remain incomplete.

## Existing-node restart verification

On October 21, 2026, one restart of the existing `contacts-local` Docker container was explicitly authorized and completed. Before and after the restart, the container identity, node image, application image IDs and named `/var` volume were unchanged. The four existing dependency PVCs retained their volume identities and were Bound. No image, volume, cluster or persisted data was deliberately deleted; this storage identity check does not independently validate every database record.

The existing containerd and kubelet services were inactive after startup and were started without changing their configuration or enablement. Docker assigned the Kubernetes API a new host port, `50267`, replacing `49437`. Administrative checks used an explicit `--server=https://127.0.0.1:50267` override; the host kubeconfig was not modified. The Kubernetes API readiness endpoint returned `ok`, and the Reporter Deployment was successfully scaled to zero with no Reporter Pods remaining.

The node reported Ready. The latest observed node memory consumption was approximately 1.806GiB against the unchanged 2.93GiB limit, with memory pressure averages at zero. Approximately 17GiB host disk space remained available. These are sampled observations, not a sustained workload capacity result.

| Check after the single restart | Observed result |
|---|---|
| Administrative API and Reporter stop | API responsive; Reporter desired/available replicas 0/0, no Reporter Pods |
| API replicas | 0/2 ready; both Pods in CrashLoopBackOff |
| MySQL, Keycloak and RabbitMQ Pods | Each 1/1 Running; application connectivity not established |
| Kafka Pod | 0/1, repeated Error/restart; observed exit code 1, not OOMKilled |
| CoreDNS | Both Pods 0/1; Kubernetes plugin not ready and upstream DNS timeouts |
| Internal Kubernetes API Service | Node request to `https://10.96.0.1:443/livez` timed out after five seconds |
| Authenticated Contact creation and dual publication | Not executed because API readiness failed |
| Stable API readiness/liveness and Pod replacement | Not established; no API Pod was deleted for this test |
| Separate Reporter consumption | Not executed; Reporter remains scaled to zero |

API startup logs recorded `UnknownHostException: mysql: Temporary failure in name resolution`. The internal API Service timeout and CoreDNS failures establish a cluster networking blocker, but do not isolate its root cause to a specific CNI or kube-proxy component. IP forwarding was observed enabled. Further network diagnosis is needed before application acceptance checks can resume.

Execution stopped after these read-only diagnostics because connection timeouts persisted. No second node restart, network setting change, memory limit change, Git operation or GitHub operation was performed. Maven tests were not rerun during this attempt. Local diagnostic logs include `/private/tmp/contacts-node-single-restart.log`, `/private/tmp/contacts-node-current-port-recovery.log`, `/private/tmp/contacts-dns-after-single-restart.log` and `/private/tmp/contacts-api-post-restart.log`; temporary files are not durable acceptance evidence. Kubernetes runtime verification remains incomplete.

## Service routing repair

On October 21, 2026, read-only comparisons isolated a service-routing failure: direct Pod-to-Pod Keycloak HTTP returned 302 and direct API Server HTTP returned 200, while both corresponding ClusterIP requests timed out. Direct CoreDNS Pod queries returned SERVFAIL; its Service address timed out. Service endpoints were present, but kube-proxy repeatedly reported NXDOMAIN for `control-plane.minikube.internal` and Kubernetes service NAT rules were absent. This evidence points to kube-proxy losing API access rather than an application fault or a complete Pod network failure.

The authorized repair changed only `data["kubeconfig.conf"]` in the `kube-system/kube-proxy` ConfigMap: the API server URL changed from `https://control-plane.minikube.internal:8443` to `https://192.168.49.2:8443`. The current node interface was verified to have that address. Before the change, a request using `/var/lib/minikube/certs/ca.crt` returned HTTP 200 with TLS verification enabled, and the API certificate SAN included `192.168.49.2`. No TLS verification was disabled.

The original ConfigMap is saved locally at `/private/tmp/contacts-kube-proxy-original-20261021.json`; the minimal rollback patch is `/private/tmp/contacts-kube-proxy-rollback.json`. These temporary backups must be retained externally if rollback is needed after temporary-file cleanup; neither contains an embedded service account token. Only kube-proxy was explicitly restarted, using its DaemonSet rollout restart to load the changed client configuration. Its replacement Pod became ready and its service and EndpointSlice informer caches synchronized.

Actual checks after repair:

- Kubernetes service NAT entries reappeared (35 matching KUBE-SVC lines in the inspected NAT output).
- Kubernetes API ClusterIP returned HTTP 200 with CA verification, both from the node and from an existing CoreDNS Pod network namespace.
- Keycloak ClusterIP returned HTTP 302.
- Both CoreDNS Pods became 1/1 Ready without an explicit restart.
- DNS via `10.96.0.10` resolved MySQL to `10.100.153.158`; queries from the existing Pod network also resolved Kafka, RabbitMQ and Keycloak.
- Kafka became 1/1 Ready without an explicit restart. Reporter remained scaled to zero.
- Sampled node usage was 2.374GiB of the unchanged 2.93GiB limit, with memory pressure averages at zero.

To revert this exact change while the current host API port remains valid:

```bash
kubectl --context contacts-local --server=https://127.0.0.1:50267 -n kube-system patch configmap kube-proxy --type=merge --patch-file=/private/tmp/contacts-kube-proxy-rollback.json
kubectl --context contacts-local --server=https://127.0.0.1:50267 -n kube-system rollout restart daemonset/kube-proxy
```

The old hostname must resolve before rollback can restore functional service routing. The fixed node IP is specific to this existing local cluster; validate it again if the cluster network changes. No node restart, memory setting change, volume deletion, Git operation or GitHub operation was performed during this repair.

After the existing five-minute CrashLoopBackOff delays expired, both API Pods recovered automatically: `contacts-api-7c5c76878d-fpc65` and `contacts-api-7c5c76878d-rdkhh` each reported 1/1 Running, and both readiness waits succeeded. Direct `/q/health/live` and `/q/health/ready` requests returned HTTP 200 for each Pod (`10.244.0.3`, `10.244.0.4`) and for the API Service (`10.97.23.75`). Kafka, MySQL, Keycloak and RabbitMQ were each 1/1 Running in the final sample. These checks establish recovery at that observation time; authenticated Contact creation, event publication and Pod replacement were not rerun as part of this narrowly scoped network repair. Maven tests were not rerun because no application code changed.

## Functional verification attempt

On October 22, 2026 (Asia/Riyadh), functional checks began against the existing cluster with Reporter kept at zero replicas. Two API Pods and all four dependency Pods were ready before and after the attempt, with unchanged restart counts during the observed interval. The API Service readiness endpoint returned HTTP 200 at the final check.

A temporary host Java client reused the existing authorization-code/PKCE login flow through local port-forwards, without launching the full verification Job or Reporter. The test password was piped directly from the existing Kubernetes Secret into the client; neither credentials nor tokens were printed or written into the repository. Login failed before an authorization code/token was obtained. Keycloak recorded `LOGIN_ERROR` with `error="cookie_not_found"`; the login submission returned HTTP 400. A bounded diagnostic attempt showed that login cookies carried `Secure; SameSite=None` while the test client used HTTP localhost port-forward URLs. This points to cookie handling in the non-browser verification path; it does not establish an invalid password or an application authentication defect. Resolving the login client/transport mismatch requires a separate decision; realm security and cookie settings were not changed.

| Check | Actual result in this attempt |
|---|---|
| API availability and dependency Pod readiness | Passed sampled checks: API 2/2; MySQL, Keycloak, RabbitMQ and Kafka each 1/1 |
| Reporter stopped | Confirmed Deployment 0/0 and no Reporter Pods |
| Keycloak token acquisition | Failed: HTTP 400, cookie_not_found; no token obtained |
| Authenticated Contact creation | Not executed because login failed |
| Matching event publication to both brokers | Not established; no test Contact/event was created |
| Delete one API Pod and verify replacement/continuity | Not executed after the authentication blocker was diagnosed |

RabbitMQ queue counters were read without fetching or acknowledging messages: the Reporter main queue and DLQ each had zero ready/unacknowledged messages and zero consumers before and after the failed login. No queue purge, broker message deletion, Kafka consumer offset change or application data cleanup was performed. No matching publication confirmation was observed in the inspected recent API logs; this is not a publication failure test because no Contact was created.

Sampled node memory usage was approximately 2.69GiB of the unchanged 2.93GiB limit. Memory pressure was nonzero (initial sampled avg10 around 1.21%, final around 0.97%); host disk free space was approximately 18GiB. Administrative requests and the final Service readiness check remained responsive, but no successful workload/recovery capacity result is claimed. Temporary port-forwards were stopped after diagnosis. No node/service restart, application configuration change, Pod deletion, memory setting change, Git operation or GitHub operation was performed. Only this documentation was changed; the functional acceptance checks remain incomplete.

## Successful functional verification

On October 22, 2026 (Asia/Riyadh), the authorized retry completed against the existing Minikube cluster with Reporter continuously at zero replicas. The temporary Java verification client explicitly replayed the login response cookies only to the validated `localhost:18081` Keycloak tunnel, resolving the Java client's handling of Secure cookies on the local HTTP test path. Cookie values stayed in memory. No realm, cookie policy, TLS setting, application code or cluster configuration was changed. This is a local test-client accommodation, not a production transport recommendation.

| Check | Actual evidence |
|---|---|
| Keycloak login | Authorization code with PKCE succeeded; token issuer matched `http://localhost:18081/realms/contacts`; token withheld from output |
| Protected API | Anonymous request returned 401; request with the token created Contact 1 with HTTP 201 |
| RabbitMQ publication | Publisher-confirm success log for the exact event below; main queue ready count increased from 0 to 1, with zero consumers and zero unacknowledged messages; DLQ stayed empty |
| Kafka publication | Successful producer acknowledgement log for the same event/contact/correlation IDs |
| Single Pod replacement | Only `contacts-api-7c5c76878d-rdkhh` was deleted with normal graceful deletion; Deployment automatically created `contacts-api-7c5c76878d-7gvtx`, which became 1/1 Ready with zero restarts |
| Service continuity | All 90 sampled iterations returned readiness HTTP 200 and protected anonymous-route HTTP 401 through the ClusterIP during and after replacement; no failed sample |
| Final readiness | API Deployment 2/2; MySQL, Keycloak, RabbitMQ and Kafka each 1/1; Reporter Deployment 0/0 |

The test identifiers were:

- Contact ID: `1`
- Event ID: `72958d1b-5f41-43b1-b56f-8196c9e036bc`
- Correlation ID: `e2721634-473c-42dc-8a9f-73a9789ad3c0`

The removed Pod UID was `8153fbf5-af52-4201-8917-48ded0e661b1`; replacement UID was `fe41a868-805f-4149-bf90-6b4c5eb625a6`. The surviving Pod retained UID `296a82e4-fbcd-4d7f-bd06-038199a3174a`. Service sampling ran from 2026-10-21 21:25:01 UTC to 21:26:37 UTC (October 22, 00:25:01–00:26:37 Asia/Riyadh). Each iteration made two bounded HTTP requests and sampled cgroup memory, with a one-second pause; this demonstrates sampled availability rather than proof of zero interruption between samples. Authenticated writes were verified before replacement, not continuously during it. A final Service liveness request also returned HTTP 200.

Publication evidence is deliberately distinguished from consumption: RabbitPublisher logs success after mandatory routing and publisher confirms; KafkaPublisher logs success after its `acks=all` send completes. Both logs matched the IDs above. Broker records were not fetched or acknowledged, Kafka offsets were not committed, and Reporter consumption was not tested. No queue, message or application record was purged; the created Contact and queued event were retained.

The memory limit remained 3,145,728,000 bytes (3000MiB). Docker working-set samples were approximately 2.68–2.73GiB, but the 90-point cgroup usage sample peaked at 3,145,723,904 bytes, nearly the limit; these are different memory accounting views. Read memory events reported `oom=0` and `oom_kill=0`; memory pressure averages were nonzero (sampled avg10 about 1.5%). Administrative commands remained responsive and dependency restart counts did not change across the attempt. This establishes success for the bounded test, not capacity for running Reporter or sustained load concurrently. Host free disk space was approximately 18GiB.

Temporary local evidence includes `/private/tmp/contacts-service-recovery-samples.log` and `/private/tmp/FunctionalCheck.java`; temporary files are not guaranteed durable. Local tunnels were stopped after verification. Only this documentation was edited in the repository. No node restart, device setting change, volume/image removal, Git operation or GitHub operation was performed. Maven was not rerun for this operational check. Separate Reporter consumption, sustained-load testing and continuous authenticated availability remain outside the results established here.

## Separate Reporter resource preflight

On October 22, 2026 (Asia/Riyadh), resources were inspected before the separately authorized Reporter consumption check. The live Reporter Deployment remained at 0/0 replicas, with a 256Mi memory request and 1Gi limit. No Reporter Pod was started.

The existing node memory limit was 3,145,728,000 bytes (3000MiB). Two cgroup usage readings were 3,145,723,904 and 3,145,687,040 bytes, both effectively at the limit. Docker reported approximately 2.7GiB working-set usage; that smaller figure excludes some reclaimable cache and must not be interpreted as guaranteed startup headroom. Anonymous memory alone was approximately 2503MiB. Memory pressure was sustained: sampled some avg60 was 2.08–2.17%, and full avg60 was 1.93–1.99%. The cgroup reported zero OOM and OOM-kill events, but absence of a previous kill does not demonstrate spare capacity. Kubernetes Metrics API was unavailable, so per-Pod metrics could not be obtained through `kubectl top`; no metrics component was installed for this check.

Given the active memory pressure and lack of reliable room for the additional JVM, execution stopped at preflight rather than forcing startup or reducing other workloads. Both API replicas and the four dependency Pods remained ready in the observed status output. No settings, resource limits or replica counts were changed.

Read-only RabbitMQ queue inspection confirmed the main queue still held exactly one ready message, zero unacknowledged messages and zero consumers; the DLQ remained empty. No basic-get, acknowledgement, purge or broker deletion was performed. The retained event from the successful publication check was not consumed.

| Reporter check | Actual result |
|---|---|
| Resource preflight | Insufficient reliable memory headroom in the current node |
| Reporter startup and health | Not executed; remains 0/0 |
| Natural consumption of retained RabbitMQ event | Not executed; message remains queued |
| Kafka event processing, result output and committed state | Not executed; earlier producer acknowledgements establish publication only |

This consumption check remains incomplete. A future attempt needs a separately approved way to free workload memory or provide more capacity; stopping existing services or changing device limits was not assumed authorized. No images, volumes, clusters, messages or application data were removed, and no Git or GitHub operation was performed. Only this documentation was updated.

## Reporter attempt with API temporarily stopped

On October 22, 2026 (Asia/Riyadh), the explicitly authorized resource-isolation check was performed. Kubernetes `/readyz` returned `ok`; the API was initially 2/2 Ready and Reporter 0/0. API replicas were scaled to zero at 2026-10-21 21:55:35 UTC (October 22, 00:55:35 Asia/Riyadh), and deletion waits confirmed both API Pods had stopped. Only the four dependency Pods remained in the namespace.

Even with API stopped, cgroup memory usage was 2,947,690,496 bytes against the unchanged 3,145,728,000-byte limit: approximately 2811MiB used and only 189MiB remaining. Docker's working-set reading was approximately 2.506GiB, a different accounting view. Memory pressure remained nonzero (some avg60 1.93%, full avg60 1.80%). This did not provide reliable headroom for Reporter's existing 256Mi request, let alone startup/transient usage up to its 1Gi limit. Reporter was therefore never scaled up, and no consumption test was forced. No other service was stopped to manufacture additional space.

API restoration was requested at 21:55:55 UTC (00:55:55 Asia/Riyadh). Its rollout completed successfully; replacement Pods `contacts-api-7c5c76878d-284cr` and `contacts-api-7c5c76878d-5886s` each became 1/1 Ready with zero restarts. By 21:56:18 UTC (00:56:18 Asia/Riyadh), the API Service returned HTTP 200 for both `/q/health/live` and `/q/health/ready`, and Kubernetes `/readyz` returned `ok`. The conservative recorded interruption window, from the scale-down request to confirmed healthy restoration, was 43 seconds; exact first/last failed application requests were not sampled during this intentional stop.

Final status was API 2/2, Reporter 0/0, and all four dependencies 1/1. Node memory events still reported zero OOM/OOM-kill events. RabbitMQ inspection confirmed the original message remained queued: one ready, zero unacknowledged, zero consumers, and empty DLQ. Neither RabbitMQ consumption nor Kafka processing by Reporter was executed or established in this attempt. Earlier dual-publisher confirmations remain publication evidence only.

No manual message acknowledgement, deletion, queue purge, data cleanup, image/volume removal, node restart, device setting change, memory limit change, Git operation or GitHub operation was performed. Only API replica counts were temporarily changed and restored; Reporter stayed stopped throughout. This check establishes that stopping the two API replicas alone does not provide sufficient reliable memory headroom for Reporter in the current environment. Reporter verification remains blocked on additional separately approved capacity or workload changes.
