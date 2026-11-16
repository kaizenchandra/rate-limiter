# 1. Requirements and assumptions

Functional scope: per-user, socket-IP, signed API-key identifier, tenant, canonical endpoint, and global Token Buckets;
tenant/tier/endpoint overrides; FREE/STANDARD/PREMIUM/ENTERPRISE tiers; bursts; versioned CRUD with disable tombstones;
effective policies; secure evaluation API; native servlet enforcement; durable audit history; distributed atomic
conjunctive deduction; optional restricted leases; OPEN/CLOSED dependency failure behavior.

Targets, **not measured guarantees**: aggregate API traffic 1M RPS with design toward 5–10M; decision p99 <5ms inside
one region; 99.99% service availability; millions of identities across independent domains. Every attempted request
still requires a rate decision unless rejected earlier by a trusted upstream guard. Rate decisions, allowed API
executions and Redis invocations are distinct counters. A hierarchy of five rules remains one EVAL, but costs more Redis
CPU and touches more keys.

Assumptions:

* Java 21; official Boot 3.5.16 compatibility verified, Maven artifacts resolved during build.
  Lettuce/Flyway/Micrometer/Security/JUnit/Testcontainers versions are BOM managed; `mvn dependency:tree` records exact
  resolved versions.
* One authoritative healthy Redis primary per slot; Redis 8.2.11; noeviction; reserved memory headroom; ACL separates
  limiter-owned keys from other clients. Failover rollback is explicitly outside healthy-primary correctness.
* Each deployed coordination domain has a complete PostgreSQL policy catalog and Redis slot. All buckets sharing a
  strict global rule reside there. Separate domains require partitioned quotas; changing domain names resets quota and
  is a controlled migration.
* Subjects, tenants, API-key IDs and tiers are assigned by a trusted issuer, not request bodies or identity headers.
  Trusted issuer must control account/key issuance to prevent Sybil attacks. IP is the direct socket peer; forwarded
  headers are ignored. With a reverse proxy this is the proxy IP until an authenticated proxy integration is explicitly
  designed.
* Capacity 1..1e9 tokens; rate .001..1e9 tokens/s; full refill <=86400s; evaluation cost 1..1e6; <=16 rule names; policy
  strings bounded; JSON <=8192 bytes; regional RTT must fit the 5ms target. A 50ms dependency timeout is a failure
  bound, not a success latency objective.
* Bucket arithmetic uses IEEE754 fractional tokens and Redis millisecond time. Integer token inputs are exactly
  representable in this range; timestamp values are well below 2^53. Avoid claiming mathematical real-number precision
  near refill boundaries. Millisecond refill discretization admits no intentionally early time credit.

Acceptance: compile/package under JDK 21; actual Redis last-token contention and rejected-hierarchy tests pass; real
PostgreSQL CRUD/history and secure 429 tests pass; fail-open/closed and stale-cache tests pass; boot and smoke request
work; Compose/Helm and CI supplied; metrics exported; benchmark reproduction supplied; unexecuted scale/failover tests
labeled. Production acceptance additionally requires TLS/JWT/audience validation, infrastructure fault drills,
memory-pressure testing, image scanning, restore verification and target-scale SLO evidence. See test matrix for gaps;
supplying a test plan does not count as passing a test.

Consistency:

| Mode           | Guarantee                                                                                                                              | Exclusions                                                                                                        |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| Direct Redis   | Serializable successful token decisions within one slot; rejected hierarchy has zero token deductions                                  | Replication rollback, script runtime/OOM failures, state eviction/reset, fail-open                                |
| Policy changes | Poll-based propagation; refuse snapshots older than 5s; Redis rejects an older revision after observing a newer consumption evaluation | Does not linearize configuration write with all requests; existing leases remain until local refresh/deadline     |
| Prepaid lease  | Grants deduct atomically; local spend cannot exceed grant; no refunds or cross-policy partial reservations                             | A request-time envelope can gain <=16N delayed tokens with N bounded lease holders; fail-open/state loss excluded |
| Regions        | Independent regional envelopes; sum is bounded only if capacities and refill rates are partitioned and never duplicated                | No implemented global allocator or strict worldwide shared bucket                                                 |

Illustrative hierarchy capacities/rates may be 1M global, 100k tenant, 1k user, 500 API-key, 100 endpoint. These are
configuration examples, never benchmark evidence. All selected rule names apply conjunctively. An override replaces a
rule; disabling a more specific rule suppresses inheritance for that rule.
