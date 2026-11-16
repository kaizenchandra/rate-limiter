# Operations, deployment and recovery

## Local and cluster deployment

Follow README for local Compose. The demo issuer is only for development, publishes ephemeral keys and privileged
tokens, and must be confined to loopback. Local Redis/PostgreSQL use an isolated bridge and loopback host ports; Redis
has no auth/TLS there. Do not expose the Compose stack externally. Remove it with `docker compose down`; omit `-v`
unless intentionally destroying data.

For representative Cluster testing:

```sh
docker compose -f docker/cluster/docker-compose.yml up -d
python3 tools/cluster-check.py
# Cleanup only this test topology:
docker compose -f docker/cluster/docker-compose.yml down
```

Cluster node addresses are internal bridge addresses. A Java app must join `limiter-cluster` and set
`SPRING_DATA_REDIS_CLUSTER_NODES=redis-0:6379,redis-1:6379,redis-2:6379`; connecting a host process to these advertised
addresses will not work without routing/port mapping. Use standalone Compose for host development. Cluster fixture is
disposable, unauthenticated and not a production Redis deployment.

The final Java/Lettuce image was also verified against this Cluster, producing 200,200,429 and retaining
restrictive-policy denial after a permissive endpoint request. To reproduce against the local Compose database/issuer
(after applying the smoke policies):

```sh
docker run --rm -d --name limiter-cluster-check --network rate-limiter_default --env-file .env \
  -e DATABASE_URL=jdbc:postgresql://postgres:5432/limiter \
  -e SPRING_DATA_REDIS_CLUSTER_NODES=redis-0:6379,redis-1:6379,redis-2:6379 \
  -e JWT_ISSUER=http://localhost:9000 -e JWT_JWK_SET_URI=http://issuer:9000/jwks \
  -e MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=false -p127.0.0.1:8082:8080 distributed-rate-limiter:verified
docker network connect limiter-cluster limiter-cluster-check
# Use a valid demo JWT to call /protected/demo on127.0.0.1:8082.
docker stop limiter-cluster-check
```

This is a separate disposable quota authority; do not route production identities to both standalone and Cluster
deployments with duplicated full budgets.

Production build/deploy:

```sh
mvn -B -ntp -Pintegration verify
docker build -t YOUR_REGISTRY/distributed-rate-limiter:RELEASE .
docker push YOUR_REGISTRY/distributed-rate-limiter:RELEASE
helm lint helm
helm template limiter helm -f production-values.yaml > rendered.yaml
helm upgrade --install limiter helm -f production-values.yaml --atomic --timeout 5m
```

Create `limiter-secrets` externally with DATABASE_PASSWORD and REDIS_PASSWORD, preferably an external secrets
controller. Override registry/tag with immutable digest at release. Provide a trusted issuer/JWKS/audience; require
TLS/mTLS through ingress or service mesh. Do not ship the local issuer. Configure managed Redis Cluster
auth/TLS/noeviction, replicas across AZs, persistence and memory headroom. Set PostgreSQL `sslmode=verify-full` and
trusted CA in the datasource URL. Install ingress body8KiB/request2s/read-rate limits, network policy allowing only
gateways/admin/monitoring and approved Redis/Postgres/identity/OTLP endpoints, and Redis ACL allowing only required
script/time/hash/string/zset/expiry commands in the limiter namespace. TLS certificates/secret rotation and network
policies are environment-specific release gates rather than fabricated cloud manifests.

Flyway migrates at startup. Deploy backward-compatible expand/contract database changes; take backups before schema
modifications. At high replica counts run migrations as a dedicated release job to avoid contention. The initial release
readiness stays down until a valid baseline policy is created; bootstrap via authenticated administrative access (e.g.
secure port-forward), then verify effective policies before routing client traffic.

Resources 1CPU/512Mi request, 2CPU/1Gi limit and HPA65% are initial sizing assumptions, not capacity results. Cap HPA to
preserve Redis socket/SQL budgets and bound lease holders, including surge/terminating pods. Redis writes cannot scale
within one global hash slot. Changing COORDINATION_DOMAIN or Redis namespace creates fresh buckets: never use that as a
casual tuning switch. Kubernetes termination grace30s exceeds Spring20s; kube-proxy/ingress draining delays must be
measured. Readiness depends on DB/Redis/current policies; liveness deliberately does not depend on them to avoid restart
storms.

## Diagnose throttling

1. Check X-RateLimit-Mode, HTTP status, Retry-After and effective policy. 429 is quota exhaustion; 503 is missing/stale
   config, saturation or uncertain coordination.
2. Read policy revision/history with an admin token. After timeout on CRUD, reread before retrying. 409 means the
   expected version was wrong; do not blindly overwrite another admin's change.
3. Compare limiter decision/Redis histograms, failures, fallback and lease metrics with HTTP latency. Inspect Redis INFO
   CPU/stats/commandstats/memory/replication and per-shard hot traffic. Keep IDs out of Prometheus labels.
4. If memory is high, reduce identity admission upstream and scale/partition domains conservatively; never enable LRU or
   FLUSHDB to restore performance. OOM during Lua can cause partial quota deductions. Move traffic to CLOSED until
   integrity is established.
5. If snapshot refresh fails, inspect PostgreSQL connectivity/pool and revision table. Five-second-old snapshots refuse
   decisions; restore DB and wait for a successful refresh. Rollback invalid policy via a new optimistic version, not
   direct DB mutation.

## Redis failure and recovery

During outage, CLOSED policies return503, OPEN deliberately admits and emits fallback alerts. Do not automatically retry
evaluation calls after timeout. Resolve topology/network/TLS/auth problems; check the authoritative primary and
replication offsets. Quantify acknowledged deduction loss from failover. If strict accounting is required, pause
admission, reestablish authoritative state and wait at least the maximum full-refill horizon (up to one day), then
resume conservatively; merely switching to a fresh Redis resets all quotas. There is no implemented consensus/durable
token ledger. AOF/replicas improve durability but do not eliminate rollback risk.

For disaster recovery restore policies/history first, validate the intended regional/domain budget ownership, fence the
old region, and account for outstanding leases and lost bucket state before transferring quotas. Static regional
capacity must not be simultaneously active in old and new regions. Practice backup restore and regional partition drills
before declaring 99.99% availability.

## Rollback and SLOs

Use `helm rollback limiter REVISION --wait`; keep database migrations compatible with the prior binary. Do not rollback
policy revision numbers or Lua namespace. Disable leasing before an emergency rollout if temporal burst exposure is
unacceptable; allow outstanding <=100ms grants to expire plus five-second configuration propagation. A known policy
rollback is a new revision, not an old snapshot.

Initial SLO proposal: 99.99% of valid evaluation attempts yield a known decision within the service deadline, excluding
intentional 429 from availability failures; <5ms regional engine p99 in a specified normal workload; fail-open counts
separately as degraded correctness. Four nines is about 4.32 minutes in a 30-day month. Page on fail-open within 1min,
sustained dependency failures >1/s for 2min, and error-budget burn (add fleet-specific fast/slow burn alerts after
baseline). Warn on decision p99>5ms for 10min and policy poll failures2min. These thresholds are starting operational
assumptions, not measured normality. Monitor Redis memory60–70% with rewrite/fragmentation headroom, replica lag and
connection counts using a Redis exporter, and database connection saturation with a PostgreSQL exporter.

JWT JWKS retrieval has500ms connect/read bounds; PostgreSQL driver connect/socket timeouts are2s, Hikari
connection/validation waits1s, Redis command timeout50ms/connect500ms, and engine concurrency128. These are failure
bounds, not the 5ms success target. Ingress must supply an overall request deadline and upload/read-rate protection.

JWT JWKS retrieval has500ms connect/read bounds; PostgreSQL driver connect/socket timeouts are2s, Hikari
connection/validation waits1s, Redis command timeout50ms/connect500ms, and engine concurrency128. These are failure
bounds, not the 5ms success target. Ingress must supply an overall request deadline and upload/read-rate protection.

Application metrics cover decisions/result/mode, engine latency, Redis latency/failures/timeouts, lease
allocations/misses, cache reads, refresh failures, fallback and JVM/process/HTTP metrics. Labels contain no identity.
The Grafana JSON is importable; replace the Prometheus datasource. Prometheus bearer token is file-mounted and rotated
externally. Structured JSON logs carry trace/span correlation; JWTs, request bodies and API-key values are not logged
deliberately. Tracing uses Micrometer OTel bridge and OTLP HTTP export with 1% sampling; verify collector reachability
and PII filtering. Security/framework warning logs can include invalid field names, so restrict log access and
retention.

## Production readiness gates

- [ ] Signed immutable image, current vulnerability/license/SBOM review; tested JDK and image digests
- [ ] Real issuer signature/issuer/audience/expired-token tests, RBAC and tenant claim review
- [ ] Secrets manager, TLS/mTLS, Redis ACL/noeviction, DB CA, network policy, ingress payload/deadline limits
- [ ] Valid baseline policies, deterministic overrides, capacity/domain partitions and config propagation SLO
- [ ] Redis memory/OOM/wrong-type and failover rollback risk accepted, replication/backup/restore drills
- [ ] HPA connection budget, PDB/surge/termination tests, bounded lease holder count if enabled
- [ ] Multi-instance/reshard/region partition/recovery and mixed-binary rollout drills
- [ ] Measured offered/achieved RPS, p99/error/overshoot/fairness under representative skew and churn
- [ ] Authenticated metrics/OTLP dashboards and on-call runbooks verified, alert ownership assigned
- [ ] Explicit approval of fail-open policies and residual quota exposure
