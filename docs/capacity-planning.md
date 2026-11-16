# Capacity estimates — not measured hardware claims

Let A be API attempts/s reaching the limiter, D decisions/s (normally A), h applicable buckets, B mean useful lease
batch, f fraction of decisions served in leasing-eligible workloads, q_app measured sustainable decisions/s per pod at
the target p99, q_shard measured sustainable scripts/s per primary for this exact h/index mix, and u=.65 operating
utilization. Direct Redis call demand Q=D; hybrid idealized Q=D[(1-f)+f/B], with extra calls for rejected batch
allocations and expired leases. Native denied attempts still count toward D. A dedicated evaluation service adds an HTTP
call per D; accepted downstream API RPS can be much smaller than A.

Required pods = ceil(D/(u*q_app)); independently routable shards >=ceil(Q/(u*q_shard)). This second formula is valid
**only for independent quota domains**. One strict domain with a shared global key remains bounded by one shard; no
number of extra shards changes that. Replicas provide availability, not write scaling.

The following scenario assumes, solely for arithmetic, q_app=25k decisions/s/pod and q_shard=50k EVAL/s/primary. These
are **unverified planning variables, not Redis/vendor capacities or application results**. Replace both from an actual
saturation test. Assume h=5, 100% direct traffic, and 1200 wire bytes per EVAL request+response including script args,
hashed keys and framing, before TLS/replication. EVALSHA cache hit is assumed; initial script-load traffic is extra.

|        D/s | Pods at 65% | Independently routable primaries at 65% | Redis traffic payload bytes/s | Gbit/s aggregate |
|-----------:|------------:|----------------------------------------:|------------------------------:|-----------------:|
|  1,000,000 |          62 |                                      31 |                      1.2 GB/s |              9.6 |
|  5,000,000 |         308 |                                     154 |                      6.0 GB/s |               48 |
| 10,000,000 |         616 |                                     308 |                     12.0 GB/s |               96 |

The Redis script performs TIME, fence GET/SET, bounded ZRANGEBYSCORE/ZREM cleanup, ZCARD, h
HMGET/ZSCORE/HSET/PEXPIRE/ZADD calls. External commands are ~one EVALSHA per decision; internal Redis command executions
are roughly 5+5h plus cleanup and cannot be ignored when measuring CPU. One 16-policy request has materially greater CPU
cost than a one-policy request. Index tracking adds O(h log M) work compared with the O(h) bucket arithmetic.

If f=.8 and B=16 useful requests, Q/D=.2+.8/16=.25, a theoretical 4x reduction. In this implementation f may be much
lower: hierarchy and variable-cost calls cannot lease. Short 100ms TTLs, isolated identities, preallocation rejection
and lease cache monitor contention can eliminate that gain. Never apply the theoretical reduction to benchmark tables as
achieved throughput.

## Memory, CPU, connections and skew

Estimate actual bytes/active bucket m with `MEMORY USAGE` plus the shared index amortization. For initial arithmetic
only, assume 512B including key/hash/zset member/allocator overhead. For I identities and h distinct per-identity
buckets, M≈I*h*m. Ten million active bucket entries would be 5.12GB before replica copies, fragmentation (assume 1.5 for
planning), safety reserve and persistence buffers: 7.68GB; one replica doubles resident copies to ~15.36GB. Shared
tenant/global/endpoint buckets reduce h. The current hard limit of 100k buckets/domain at this assumption is ~51.2MB
before overhead reserves; millions of identities require many domains and an intentional quota partition, not silently
raising that bound.

Worst-case key creation <=accepted requests/s*h, bounded by the index cap per domain. TTL is ceil(C/r*1000)+1s and
conservative backward-clock padding. Cardinality index cleanup removes at most 64 expired members/evaluation. Redis
memory must accommodate all allowed entries plus persistence/replication buffers; `noeviction` prevents reset-based
overspend but OOM inside Lua can leave partial writes. Refuse traffic and remediate before OOM. Monitor
used_memory/maxmemory at <60–70% as a starting threshold, calibrated from fragmentation and rewrite peaks.

CPU: do not infer cores from RPS alone. Measure wall and CPU seconds per decision, system time, TLS, Redis event-loop
utilization, and GC. CPU demand≈D*CPU-seconds/decision. Pod count above assumes the measured q_app already meets p99 and
leaves 35% headroom. Database reads are pods / pollInterval full-catalog snapshots/s; 616 pods polling 10k documents is
expensive. Larger deployments require a compiled catalog and conditional revision fetch/delta distribution before scale
acceptance.

Lettuce multiplexes, so there is no blocking connection pool per request. Budget socket count≈pods
*reachable_cluster_nodes*1..2 plus topology/health/admin clients, and bounded reconnect pressure. For 62 pods and 62
nodes, naively budgeting two connections/node gives ~7688 sockets across the deployment. Restrict each deployment to
relevant Redis partitions; measure actual sockets rather than opening every pod to every global shard. Postgres pool <=
4/pod (62 pods→248 possible connections); use a connection proxy and conditional polling before scaling to hundreds of
pods.

Zipf/hot tenant: if fraction z of decisions reaches one slot, that shard must sustain zD regardless of average fleet
utilization. A 20% tenant at 1M D/s demands 200k evaluations/s at its primary—already above the illustrative q_shard.
Consistent hashing cannot split a bucket. Mitigations: region/tenant budget partitions, dedicated hot-tenant deployment,
policy-approved single-rule leases, upstream rejection and negotiated tenant quotas. Splitting a user's one bucket into
K independent full buckets incorrectly multiplies quota; allocate C/K and r/K or use a specified allocator instead.

Multi-region: central coordination adds approximately WAN RTT to every decision and transfers Q*wireBytes across
regions; packet loss and partitions dominate tails. Static budgets avoid per-request WAN traffic but waste idle quota.
Dynamic grants reduce WAN to grants/s yet require durable grant ownership/fencing, bounded outstanding regional quotas
and recovery drills. Cost drivers: provisioned hot-shard CPU, replicas, memory, persistence/backup storage, AZ
replication and egress, application CPU/JWT validation, load generators, observability cardinality/retention, managed
database connections and standby regions. Obtain current cloud quotes for measured topology; no dollar figures are
fabricated here.
