# Mandatory architecture review

1. **Why Token Bucket over Fixed Window?** Capped continuous refill gives a defined burst and avoids fixed-window
   boundary doubling; constant per-bucket state suits high cardinality.
2. **How is consumption atomic?** Redis runs one same-slot Lua script without interleaved commands; it computes every
   balance before writing. Runtime errors have no rollback, so memory/type/ACL integrity are assumptions.
3. **Multiple instances consume final token?** The first successful script deducts it; following scripts see
   insufficient balance, assuming no intervening refill.
4. **Can failover overspend?** Yes. Asynchronous replicas can lose deductions/fences/index state; no strict failover
   guarantee is claimed.
5. **How reach 10M RPS?** Measure pod/shard capacity, partition independent tenant/region budgets, distribute load
   generators and optimize measured bottlenecks. One strict global slot cannot simply be sharded. The supplied baseline
   has not achieved 10M RPS.
6. **When is Redis the bottleneck?** Hot slots, expensive hierarchies/cardinality index, CPU, network, queueing,
   persistence and replication can dominate before fleet averages do.
7. **How do local quotas reduce traffic?** Upfront min(16,C) allocation amortizes one Redis call across useful local
   spends; sparse/short-lived leases waste tokens and may not help.
8. **What does leasing sacrifice?** Request-time smoothness, perfect cross-pod fairness and utilization; <=N*16 delayed
   tokens per bucket with bounded holders, excluding state loss/fail-open/policy lag. No cross-policy lease atomicity is
   attempted.
9. **Prevent hot keys?** Dedicated quota domains, statically partitioned rates/capacities, upstream protection and
   optional single-rule grants. Hashing a single logical counter does not remove its serialization requirement.
10. **Several policies without partial consumption?** Co-locate all applicable buckets and prevalidate before one commit
    phase. Cross-slot atomic hierarchy is deliberately unsupported.
11. **Policy changes in flight?** Atomic durable revision, fresh snapshots, stable state keys, min-rate transition,
    newer-success revision fence and bounded propagation; old requests can still apply until cutoff. Local revisions
    invalidate leases.
12. **Redis unavailable?** CLOSED503; OPEN only if every selected policy permits it, explicitly metered. No config
    always refuses. Bulkheads/timeouts prevent unbounded buildup.
13. **Ambiguous network timeout?** Do not retry/refund consumption. Return unavailable for CLOSED; account for possible
    lost quota, separate from business-operation idempotency.
14. **Multiple regions?** Recommend statically partitioned C/r; dynamic regional allocator is future work. One
    centralized authority imposes WAN latency/partition availability trade-offs.
15. **High-cardinality attacks?** JWT-owned identities, controlled issuer/key issuance, bounded fields/body/catalog,
    fixed canonical native route and max100k tracked Redis buckets/domain; refuse creation at capacity. IP-based proxy
    behavior needs explicit integration.
16. **MVC/WebFlux/virtual threads?** MVC keeps JDBC/control plane and servlet integration simple. Explicit bounds
    compensate for blocking. Reactive or virtual-thread alternatives need measured evidence and retain Redis/concurrency
    limits.
17. **Connections during autoscaling?** Shared multiplexed Lettuce connections, bounded queues, no per-request pool, HPA
    cap, relevant-shard routing, topology refresh control; SQL <=4/pod and future conditional polling/proxy for larger
    fleets.
18. **Prove concurrency correctness?** Actual Redis tests assert admitted count under same-key contention and no
    deductions after hierarchy rejection; fractional/refill/bounds/property tests complement them. Two adapters are not
    a1000-pod proof; failures require separate drills.
19. **Validate million RPS scientifically?** Distributed open-model arrival generators, synchronized boundaries,
    adequate CPU/network and identity tokens; record offered/achieved/dropped iterations, full server/exporter
    telemetry, p50/p95/p99 and overshoot/fairness; repeat independent runs and retain raw artifacts.
20. **Production prerequisites?** Pass the readiness gates in runbook, measure target topology, review
    security/dependency lifecycle, accept consistency/rollback limits and execute outstanding chaos/restore drills.
    Functional tests alone do not certify production capacity.

Hardening completed during implementation: server-side time, stable keys across updates, scoped signed identities,
unknown-field rejection, nonwaiting concurrency bounds, bounded Redis index, no retries for ambiguous operations, status
projection without token deduction, explicit staleness cutoff, disabled tombstones, rollback-aware documentation and
real dependency tests. Remaining scaling limits include O(catalog) policy resolution, one-monitor lease cache, one
slot/domain, cardinality-index CPU, full-catalog polling, conservative snapshot health and no global allocator. Optimize
only with measurements; do not represent proposed distributed allocation as implemented.
