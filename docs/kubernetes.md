# API on Minikube

Only `contacts-api` runs inside Kubernetes: two replicas, a ClusterIP Service, ConfigMap and a locally created Secret. MySQL, Keycloak, RabbitMQ, Kafka and Reporter run in Docker Compose. Java 21, the existing non-root image, Flyway, transactional outbox and both publishers are unchanged. No Helm, Ingress, monitoring installation, dependency StatefulSets, PVC manifests or verification Jobs are needed.

`k8s/` contains only `namespace.yaml`, `configmap.yaml`, `deployment.yaml` and `service.yaml`. The Secret is created from the same local credentials used by Compose, never committed. The API has a 256Mi memory request and 512Mi limit per replica, UID/GID 10001, startup/liveness `/q/health/live`, and readiness `/q/health/ready`.

## Preconditions and existing data

Use Docker Desktop, Compose supporting `!override`, kubectl and Minikube with the Docker driver. The current running images are `contacts-api:minikube-20261020` and `contacts-reporter:minikube-20261020`. The new Observability source prepares `contacts-api:observability-20261022` and `contacts-reporter:observability-20261022` in the manifests; build them and obtain deployment approval before applying those changes. Local credential initialization still reuses `contacts-reporter:minikube-20261020`. Both are existing local images, not published commit tags. Their runtime is Java 21; rebuilding uses the existing root-context Dockerfiles. Never substitute `latest` alone.

The current node's Docker network is `contacts-local`, subnet `192.168.49.0/24`, node address `192.168.49.2`. `compose.kubernetes.yaml` attaches the external services to this existing network using fixed addresses .10–.14; no host networking changes are needed. Verify the subnet and address availability before startup:

```bash
docker network inspect contacts-local --format '{{range .IPAM.Config}}{{.Subnet}}{{end}} {{range .Containers}}{{.Name}}={{.IPv4Address}} {{end}}'
minikube -p contacts-local status
kubectl --context contacts-local get --raw=/readyz
```

If the network differs or an address is occupied, stop and revise both the override and ConfigMap/hostAliases before starting anything. Do not recreate a cluster to obtain these addresses. For a new, empty environment only, `minikube start -p contacts-local --driver=docker` creates the network; resource suitability must be checked first. Compose and Minikube share Docker Desktop's existing memory budget; the bounded concurrent test below passed, but sustained-load capacity was not measured.

The approved transition is complete. The current Compose project is `contacts-clean-final-20261020`, reusing all six existing named volumes. Old Kubernetes dependencies and Reporter are retained at zero replicas, and all four PVCs remain Bound with their original volume IDs. No Kubernetes data was copied into Compose or deleted. The earlier queued Kubernetes event remains in the stopped broker's separate data store; the successful new test used the existing Compose database and brokers.

The transition snapshots, private runtime credentials and rollback manifest are under `/private/tmp/contacts-compose-transition-20261022/` (directory 0700, sensitive files 0600). They must remain outside the repository. Temporary storage is not a durable backup: preserve these files in an approved private location before cleanup/reboot. For commands in this guide against the current environment, set these shell-local Compose options first:

```bash
export COMPOSE_PROJECT_NAME=contacts-clean-final-20261020
export COMPOSE_ENV_FILES=/private/tmp/contacts-compose-transition-20261022/runtime.env
```

The private runtime file supplies the existing Reporter's client credential and pins RabbitMQ's original hostname, `755345e849a5`. Do not replace it with a different project's `.env`. Keeping the RabbitMQ hostname is necessary when reusing its node data. These options do not change device settings. For a separate empty environment, choose its project explicitly and use its own credentials; never point it at these volumes accidentally.

## Start, verify and stop safely

The current adopted containers are already attached to the shared network. Start them without recreating the dependency containers, preserving RabbitMQ's node identity and every data mount:

```bash
docker compose -f compose.yaml -f compose.kubernetes.yaml config --quiet
docker compose -f compose.yaml -f compose.kubernetes.yaml start mysql keycloak rabbitmq kafka
docker compose -f compose.yaml -f compose.kubernetes.yaml ps
```

Wait for all four dependencies to be healthy. For a separate empty environment only, `up -d --no-build mysql keycloak rabbitmq kafka` creates containers. Do not start another copy of the old Kubernetes dependencies. Existing containers were attached once using `docker network connect --ip ADDRESS contacts-local CONTAINER`, adding `--alias kafka` for Kafka; do not repeat attachment when already connected.

API connects to MySQL .10:3306, Keycloak .11:8080, RabbitMQ .12:5672 and Kafka .13:19092. Kafka's unchanged INTERNAL listener advertises `kafka:19092`, resolved in API Pods through `hostAliases`. Never use its localhost EXTERNAL listener from Pods. Compose Reporter also uses explicit dependency addresses, avoiding missing aliases on adopted containers. Keycloak's public issuer is `http://localhost:8081/realms/contacts`; API verifies that issuer and signatures while fetching JWKS from the internal .11 address with discovery disabled. Localhost in the issuer is an identity value, not a Pod connection target.

For a stopped API, ensure its Secret matches the existing Compose credentials before deployment. This pipeline streams the credentials without displaying or saving them in the repository; do not enable shell tracing. Existing initialized databases must retain their actual passwords, not newly generated ones.

```bash
set -euo pipefail
set +x
kubectl --context contacts-local apply -f k8s/namespace.yaml
{
  docker compose -f compose.yaml -f compose.kubernetes.yaml exec -T mysql sh -c 'printf "mysql-app=%s\n" "${MYSQL_PASSWORD:-$(cat /run/development-secrets/mysql-app)}"'
  docker compose -f compose.yaml -f compose.kubernetes.yaml exec -T rabbitmq sh -c 'printf "rabbitmq=%s\n" "${RABBITMQ_DEFAULT_PASS:-$(cat /run/development-secrets/rabbitmq)}"'
} | kubectl --context contacts-local -n contacts-local create secret generic contacts-credentials --from-env-file=/dev/stdin --dry-run=client -o yaml | kubectl --context contacts-local apply -f -
minikube -p contacts-local image load contacts-api:observability-20261022
kubectl --context contacts-local apply -f k8s/
kubectl --context contacts-local -n contacts-local rollout status deployment/contacts-api --timeout=180s
kubectl --context contacts-local -n contacts-local get pods,deployments,endpointslices
```

The documented fixed image is already built/imported. If rebuilding after source changes, build the existing API Dockerfile from repository root with a new explicit tag and update Deployment to match. Keep secrets and `.env` outside the image; do not change the existing Java 21 Dockerfile or base Compose for this topology.

If the host kubeconfig has a stale API port, inspect `docker port contacts-local 8443/tcp` and pass the matching `--server=https://127.0.0.1:PORT` explicitly. The current value is `--server=https://127.0.0.1:50267`. Do not disable TLS validation. These manifests do not modify control-plane configuration.

For host access only, run `kubectl --context contacts-local -n contacts-local port-forward service/contacts-api 18080:8080`. Compose Reporter's optional HTTP endpoint also needs the host tunnel reachable through Docker Desktop. In a dedicated terminal, use this alternative:

```bash
kubectl --context contacts-local -n contacts-local port-forward --address=0.0.0.0 service/contacts-api 18080:8080
```

This binds all host interfaces; restrict use to the trusted local development network and retain API authentication. It does not change device settings. The override points Reporter to `http://host.docker.internal:18080`. After API is ready and memory is sufficient, start one Reporter with the private Compose runtime options exported above:

```bash
docker compose -f compose.yaml -f compose.kubernetes.yaml up -d --no-deps --no-build contacts-reporter
curl --fail http://localhost:18080/q/health/ready
curl --fail http://localhost:18080/q/health/live
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:18080/api/v1/contacts
curl --fail http://localhost:8082/reports/contacts/count
```

Expect API 2/2, health HTTP 200, anonymous API HTTP 401, and Reporter HTTP 200. Use the existing [Java device-login tool](../identity/TOOLS.md) against `http://localhost:8081/realms/contacts`, saving a token privately outside the repository. A browser handles secure localhost cookies; do not weaken Keycloak or enable password grants for a test. To create a unique test Contact without printing the bearer token:

```bash
{
  printf 'header = "Authorization: Bearer '
  cat /tmp/contacts-user.jwt
  printf '"\n'
} | curl --config - --fail-with-body -H 'Content-Type: application/json' --data '{"firstName":"Kubernetes","lastName":"Check","email":"kubernetes-check@example.test"}' http://localhost:18080/api/v1/contacts
kubectl --context contacts-local -n contacts-local logs -l app.kubernetes.io/name=contacts-api --since=5m --tail=-1 | rg 'contact-created published|publication deferred'
docker compose -f compose.yaml -f compose.kubernetes.yaml logs --since=5m contacts-reporter | rg 'contact-created consumed|kafka contact-created processed|duplicate'
```

Use a fresh email on repeated checks. Compare contact/event/correlation IDs across both publisher confirmations and Reporter processing. Publisher logs alone do not establish consumption. Do not purge queues or manually acknowledge broker messages.

To sample actual Service availability independently of the host tunnel, run in one terminal:

```bash
api_service_ip=$(kubectl --context contacts-local -n contacts-local get service contacts-api -o jsonpath='{.spec.clusterIP}')
docker exec contacts-local sh -c 'for i in $(seq 1 60); do date -u; curl --max-time 2 -s -o /dev/null -w "ready=%{http_code}\n" "http://$1:8080/q/health/ready"; sleep 1; done' sh "$api_service_ip"
```

For an authorized recovery test only, in another terminal record Pod UIDs, delete exactly one Pod, then wait for the actual replacement's Ready condition and confirm two ready replicas:

```bash
kubectl --context contacts-local -n contacts-local get pods -l app.kubernetes.io/name=contacts-api -o wide
pod=$(kubectl --context contacts-local -n contacts-local get pods -l app.kubernetes.io/name=contacts-api -o jsonpath='{.items[0].metadata.name}')
kubectl --context contacts-local -n contacts-local delete pod "$pod" --wait=false
kubectl --context contacts-local -n contacts-local wait --for=delete "pod/$pod" --timeout=120s
kubectl --context contacts-local -n contacts-local get pods -l app.kubernetes.io/name=contacts-api
kubectl --context contacts-local -n contacts-local wait --for=condition=Ready pod -l app.kubernetes.io/name=contacts-api --timeout=180s
kubectl --context contacts-local -n contacts-local get deployment contacts-api
```

Confirm the replacement exists before the Ready wait: rollout status immediately after deletion can briefly show the previous state. Compare old/new UIDs and check the sample results. A host port-forward binds one Pod, so restart it if its selected Pod was replaced. Monitor `docker stats --no-stream` and node memory pressure; stop testing on timeouts or severe pressure. Diagnose using `kubectl describe pod`, filtered API/Reporter logs and Compose health; never print Secret objects or raw tokens.

For an authorized safe shutdown, scale only API to zero, stop Reporter and the Compose dependencies with `docker compose -f compose.yaml -f compose.kubernetes.yaml stop contacts-reporter mysql keycloak rabbitmq kafka`, then stop the host forward with Ctrl-C. Keep all volumes and the shared network. Never use pruning, `down -v`, namespace deletion or cluster deletion.

## Actual verification status

The simplified topology was deployed and tested on October 22, 2026 (Asia/Riyadh). Only two API workload Pods run in Kubernetes. The four old StatefulSets and old Reporter Deployment remain at zero replicas solely to preserve rollback; their PVCs and Services were not deleted. MySQL, Keycloak, RabbitMQ, Kafka and one Reporter container run in the existing Compose project and are healthy.

| Check | Actual result |
|---|---|
| Data reuse | Four dependency container IDs and all named-volume mappings unchanged; Reporter container recreated for its new connection configuration, using the same credentials and state volumes |
| MySQL/Flyway | Existing Contact count was 1 before the test and 2 afterward; four migration records remained; outbox count was 0 after publication |
| API and probes | Deployment 2/2; both Pods and ClusterIP returned HTTP 200 for live/ready |
| Keycloak/JWT | Authorization Code with PKCE succeeded against existing Compose Keycloak; issuer matched `http://localhost:8081/realms/contacts`; anonymous API HTTP 401, authenticated creation HTTP 201 |
| Dual publication | Both RabbitMQ publisher-confirm and Kafka acknowledgement logs matched the new event/contact/correlation IDs below |
| RabbitMQ processing | Natural Reporter consumption logged success on attempt 1; queue went 0 → 1 → 0; no manual fetch, acknowledgement or purge |
| Kafka processing | Reporter logged count 1; a read-committed probe found exactly one matching result at partition 2, offset 0, key 2; probe used explicit partition assignment, no group and no offset commits |
| Reporter HTTP | `/reports/contacts/count` returned HTTP 200 after using the matching existing client credential |
| Recovery | One API Pod deleted; replacement became ready; all 60 ClusterIP samples returned ready=200 and anonymous=401 |
| Maven regression | Offline verify passed after transition: 234 cases, 231 executed successfully, three existing opt-in broker tests skipped, no failures/errors; unchanged Coverage gates passed |

Test Contact ID `2`, event ID `a6c56b6a-565e-4445-a803-d225680b9dca`, correlation ID `8363183d-887e-49c9-b33b-76dc4a23f485`. These identifiers refer to the new test in Compose; they do not refer to the separate Contact/event retained in Kubernetes PVCs.

The old API was scaled down at 14:53:11 UTC (17:53:11 Asia/Riyadh). The surviving new Pod first became Ready at 14:54:39 UTC (17:54:39), an elapsed 88 seconds from the scale-down request. Exact last-failed/first-successful client responses during migration were not continuously sampled. Contact creation was confirmed at 14:55:22 UTC.

During recovery, `contacts-api-69ccb987d-h55s4` (UID `da7cde1c-8e04-4fc5-971c-7b33fe76691c`) was deleted at 15:02:15 UTC. Replacement `contacts-api-69ccb987d-chbhl` (UID `5a96d7c5-1088-4e63-87d3-2228a5bd1c0c`) was observed 1/1 Ready 19 seconds later, with zero restarts. Surviving Pod `contacts-api-69ccb987d-66rpp` retained UID `da6561fb-0e15-42c6-9a43-0608988004f9`. The 60 Service sampling iterations ran 15:01:52–15:02:57 UTC; no failed sample was recorded. This is sampled health/anonymous-route availability, not proof of uninterrupted authenticated writes between samples. The host port-forward is separate and can require restarting if its selected Pod is replaced.

Two startup configuration issues were corrected without changing application logic or identity data: adopted containers lacked some Compose service aliases on the additional network, so Reporter now uses fixed dependency addresses; its initial client credential selection did not match the persisted realm. A read-only Keycloak admin lookup recovered the existing credential, confirmed it matched the development-secret volume, and supplied it through the private runtime env file. The realm and client credential were not reset or rotated. Reporter was recreated using the same state volume; Kafka returned to RUNNING, and RabbitMQ was consumed only through its normal handler.

Final node working-set usage was approximately 1.43GiB of its unchanged 2.93GiB limit; the recovery cgroup peak was 1,932,447,744 bytes. Node memory pressure avg10/avg60 was zero in the final sample, and OOM/OOM-kill counters remained zero. Compose memory samples were approximately Reporter 203MiB, RabbitMQ 46MiB, MySQL 92MiB, Keycloak 403MiB, Kafka 322MiB. Dependencies share Docker Desktop's existing memory budget; these bounded samples do not establish production or sustained-load capacity. All dependency containers were still healthy in the final post-Maven check.

Maven ran on host JDK 22 with compiler `release=21`; the actual API image was separately confirmed as Temurin 21.0.12.1. No claim is made that this Maven run used JDK 21. No application logic, CI/CD or Coverage thresholds were changed. Dockerfile builds still use Java 21. A Maven rerun under JDK 21 remains a parity check, not an observed result.

Local evidence is under `/private/tmp/contacts-compose-transition-20261022/`: `kubernetes-original.json`, `compose-original.json`, `compose-final.json`, `network-original.json`, `kafka-committed-result.log`, `recovery-samples.log` and `maven-verify.log`. Raw snapshots contain credentials: never publish them or include them in source control. No Git/GitHub operation, node restart, image removal, volume removal, database reset or permanent Kubernetes resource deletion was performed. The current host tunnel on port 18080 remains running for Reporter HTTP access; stop it only when that access is no longer needed. Earlier results are in [the historical verification archive](kubernetes-history.md).

## Roll back without deleting data

Rollback was prepared but not executed because the transition succeeded. It restores old Kubernetes credentials/configuration, then the old dependency replicas, then two API replicas. It stops Compose containers but retains their data, including the new Contact and processed event state. The additional Docker network connections can remain; no detach/delete is needed. Only run this sequence when rollback is authorized:

```bash
kubectl --context contacts-local --server=https://127.0.0.1:50267 -n contacts-local scale deployment/contacts-api --replicas=0
kubectl --context contacts-local --server=https://127.0.0.1:50267 -n contacts-local wait --for=delete pod -l app.kubernetes.io/name=contacts-api --timeout=120s
docker compose --env-file /private/tmp/contacts-compose-transition-20261022/runtime.env -p contacts-clean-final-20261020 -f compose.yaml -f compose.kubernetes.yaml stop contacts-reporter mysql keycloak rabbitmq kafka
kubectl --context contacts-local --server=https://127.0.0.1:50267 apply -f /private/tmp/contacts-compose-transition-20261022/rollback-settings.json
kubectl --context contacts-local --server=https://127.0.0.1:50267 -n contacts-local scale statefulset/mysql statefulset/keycloak statefulset/rabbitmq statefulset/kafka --replicas=1
for service in mysql keycloak rabbitmq kafka; do
  kubectl --context contacts-local --server=https://127.0.0.1:50267 -n contacts-local rollout status statefulset/$service --timeout=300s || break
done
```

Confirm all four old dependencies are Ready before restoring API:

```bash
kubectl --context contacts-local --server=https://127.0.0.1:50267 -n contacts-local get statefulsets,pods
kubectl --context contacts-local --server=https://127.0.0.1:50267 -n contacts-local scale deployment/contacts-api --replicas=2
kubectl --context contacts-local --server=https://127.0.0.1:50267 -n contacts-local rollout status deployment/contacts-api --timeout=300s
```

The old issuer is `http://localhost:18081/realms/contacts`; restore the documented Keycloak port-forward for that old topology when testing its authentication. Stop the current API forward with Ctrl-C and reopen it if necessary. Existing Reporter in Kubernetes remains at its original zero replicas. Do not use pruning, `down -v`, PVC deletion or credential regeneration during rollback.
