# Architecture decision records

## ADR 001 — Token Bucket and atomic hierarchy

Accepted: Token Bucket with Redis server time; one script reads/checks <=16 buckets then deducts all on success. Smooth
refill and configurable bursts suit APIs. Fixed windows are cheaper but have boundary bursts. GCRA remains a possible
measured optimization. Fractional double arithmetic uses bounded token/rate values. Lua atomic execution serializes
decisions; it does not roll back runtime errors.

## ADR 002 — One strict coordination domain per Redis hash slot

Accepted: common hash tag for buckets, policy fence and identity index. Guarantees all-or-none deduction in normal
operation. A single global rule is a hot key and prevents write-sharding; exact cross-slot multi-policy transactions are
not implemented. Throughput scale requires independent domains with partitioned quotas. Strict worldwide global
enforcement is not claimed.

## ADR 003 — Modular monolith and embedded default

Accepted: MVC REST control/evaluation APIs and servlet enforcement share domain/application/infrastructure components.
No Kafka/CQRS/service discovery: no concrete need for another durable streaming system. JDBC is confined to
control-plane polling/writes. Gateway extraction of engine packages can follow after stable integration requirements; a
reusable SDK artifact is not separately packaged yet.

## ADR 004 — Configuration revision protocol

Accepted: PostgreSQL transactional revision row, optimistic If-Match mutations, immutable audit history, repeatable-read
snapshots, 1s polling and 5s staleness cutoff. Tenant > tier > endpoint specificity weighted 4/2/1; lexicographically
smaller policy ID wins ties. Disabled override suppresses an inherited rule. Stable policy-ID bucket keys avoid free
full-bucket resets on every version. Distinct effective policy IDs never share balance or local leases; new policy IDs
intentionally allocate independent buckets. On parameter changes, cap balance and refill at min(old,new) rate for the
elapsed transition interval, conservatively under-admitting after increases. A new Redis consumption revision fences
older evaluations. This is bounded propagation, not synchronous worldwide rollout.

## ADR 005 — Failure behavior and ambiguous consumption

Accepted: no automatic application retries on Redis runtime/timeout failure. CLOSED returns 503; OPEN only if all
selected rules allow it, with explicit fallback telemetry. Redis Cluster routing redirects can retry commands rejected
before execution; they are bounded at two. EVALSHA NOSCRIPT recovery is safe because NOSCRIPT means no execution. Lost
response may consume quota without delivering an allowed response. Avoid refunds: they can mint tokens after another
request or failover.

## ADR 006 — Optional restricted leases

Accepted: single applicable rule, cost=1, policy lease flag and deployment flag both required. Grant min(16,C), prepaid
atomically; local synchronized spend, <=100ms deadline measured from before grant RPC, no refunds/reclaims, <=10k
entries. Expired grants are discarded. Multi-rule leases are not supported because independent reservations can
partially consume unrelated quota. Cluster-wide membership/lease-holder bound is operational, not automatically
discovered. Observed local revision invalidates grants. Failed batch allocation may safely try cost=1 only after a known
rejection.

## ADR 007 — Security and bounded resources

Accepted: JWT issuer/audience/signature checks, scoped administration/evaluation, signed tenant claims, socket IP only,
hashed bucket/actor identifiers, 8KiB body bound, bounded catalog/identity/lease state, 128 concurrent evaluations and
256 queued Lettuce commands/connection. No metrics labels include user/tenant/key/endpoint IDs. Local demo issuer is
forbidden in production. Redis ACL/TLS, PostgreSQL TLS and network policy remain deployment responsibilities.
