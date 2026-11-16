# 7. Actual evidence and benchmark limitations

## Final local HTTP samples — 13 November 2026

These are short diagnostic samples, **not a production capacity validation**. Host: Mac15,6 arm64,11 logical CPUs,18GiB
physical RAM; shared workstation. Docker 29.8.2/OrbStack exposes11 CPUs/~8.79GiB VM memory. k6 v2.4.0 runs on the host;
the final app runs inside the built non-root Temurin 21.0.13+9 image, and Redis 8.2.11/PostgreSQL 17.12 run on the same
Docker host. A second app instance, six idle Cluster-fixture nodes and normal workstation/runtime processes are present.
No Kubernetes production deployment, WAN, TLS or cloud hardware is involved. Container CPU/memory limits were not set in
these samples.

Path: k6→IPv4 loopback published port→Docker app MVC/JWT/resolver/Lettuce→same-host Redis→HTTP response. One signed
identity, one effective USER policy, cost1,capacity=rate=1e9,leasing=false,two-document catalog. No actual business API
work. Every completed decision in the final samples was allowed. HTTP timing includes authentication, network, queues
and serialization; it is **not** the engine-only timer. Five seconds at 1000/s warmed the final image before two 10s
measured stages; this is far shorter than the full protocol's warmup/soak. No build/integration-test work ran
concurrently with these final samples, but the machine was not dedicated or isolated.

| Offered decisions/s | Achieved completed HTTP/s incl. drain | Completed / allowed | 429 | Errors | Dropped arrivals | HTTP p50 ms | HTTP p95 ms | HTTP p99 ms |
|--------------------:|--------------------------------------:|--------------------:|----:|-------:|-----------------:|------------:|------------:|------------:|
|               1,000 |                                 999.8 |              10,000 |   0 |      0 |                0 |       0.691 |       1.328 |       4.664 |
|              10,000 |                               9,782.1 |              97,832 |   0 |      0 |            2,169 |       1.321 |      63.525 |      97.699 |

The 1000/s sample met its offered rate approximately with no dropped arrivals. The 10000/s stage **failed the
dropped-iterations threshold** and did not sustain its offered rate; the tail latency is far above 5ms end-to-end.
Engine-only p99 was not isolated for these stages, so neither sample proves the regional engine SLO. Errors=0 does not
mean the offered workload was fully served: dropped arrivals never reached the app. These data establish no million-RPS
result.

Exact final commands, from project root with the supplied fixture already applied and a private signed-token file:

```sh
k6 run -e BASE_URL=http://127.0.0.1:8080 -e RATE=1000 -e DURATION=5s -e VUS=100 -e MAX_VUS=500 -e DISTRIBUTION=hot -e ENDPOINT=/benchmark -e TOKENS_FILE=/tmp/rl-bench-tokens.json -e RESULT=benchmarks/results/http-final-warmup.json benchmarks/k6/evaluate.js
k6 run -e BASE_URL=http://127.0.0.1:8080 -e RATE=1000 -e DURATION=10s -e VUS=100 -e MAX_VUS=500 -e DISTRIBUTION=hot -e ENDPOINT=/benchmark -e TOKENS_FILE=/tmp/rl-bench-tokens.json -e RESULT=benchmarks/results/http-final-1000.json benchmarks/k6/evaluate.js
k6 run -e BASE_URL=http://127.0.0.1:8080 -e RATE=10000 -e DURATION=10s -e VUS=200 -e MAX_VUS=1000 -e DISTRIBUTION=hot -e ENDPOINT=/benchmark -e TOKENS_FILE=/tmp/rl-bench-tokens.json -e RESULT=benchmarks/results/http-final-10000.json benchmarks/k6/evaluate.js
```

[Raw1000/s summary](../benchmarks/results/http-final-1000.json), [raw10000/s summary](../benchmarks/results/http-final-10000.json), [image/hardware metadata](../benchmarks/results/environment.json), [source hashes](../benchmarks/results/source-sha256.txt).
Earlier `http-*-denial-initial.json` files retain the denial-heavy policy-transition diagnostic (initial p99 was not
configured). Earlier host-JVM and container runs remain labeled separately; some overlapped builds/integration work and
must not be merged with final samples or cherry-picked to claim capacity.

## Java adapter diagnostic

One thread,one hot identity,1000 warmup calls then 10000 measured calls per mode,host Oracle JDK 21.0.13.0.3 to local
Redis. Local-only bypasses HTTP and all distributed enforcement; direct and lease adapters bypass HTTP/JWT/policy
resolution. Each mode admitted all 10000. Values are microseconds for per-operation percentiles.

| Mode            | Observed operations/s in this short batch |  p50 μs |  p95 μs |  p99 μs |
|-----------------|------------------------------------------:|--------:|--------:|--------:|
| local-only-java |                              11,897,099.6 |   0.042 |   0.084 |   0.250 |
| direct-redis    |                                   6,560.7 | 154.417 | 220.625 | 319.000 |
| prepaid-lease   |                                  89,469.4 |   1.042 | 150.625 | 171.042 |

[Raw CSV](../benchmarks/results/adapter-comparison.csv). This hand-timed comparison has no JMH forks/confidence
intervals and can be distorted by tiered compilation, timer overhead and shared-machine activity. The local-only batch
lasts around one millisecond and is especially unsuitable for throughput extrapolation. It demonstrates a candidate
optimization to measure properly, **not** server capacity or a proven speedup under realistic traffic. Leases remain
disabled by default and cannot apply to conjunctive hierarchies.

## Target stages and remaining measurements

| Offered stage | Status                                                                                              |
|--------------:|-----------------------------------------------------------------------------------------------------|
|       10000/s | Short local attempt executed; dropped-arrival threshold failed; not validated as sustained capacity |
|      100000/s | **Not tested**                                                                                      |
|      500000/s | **Not tested**                                                                                      |
|     1000000/s | **Not tested**                                                                                      |
|     5000000/s | **Not tested**                                                                                      |
|    10000000/s | **Not tested**                                                                                      |

No target-scale uniform/Zipf/churn/hierarchical/hybrid/WAN/HPA/failure benchmark was executed. Redis CPU/memory/network
time series, application JFR allocation/lock profiles, quota overshoot over sustained windows, per-tenant fairness,
utilization and infrastructure cost were **not collected** for the final HTTP samples. Health and metric exposition were
checked functionally; that is not a full monitoring campaign. The local Java contention tests establish healthy-primary
invariants for their bounded workloads, not a fairness or capacity result.

The final10000/s run shows queueing/saturation and generator drops. Its low median with large tails is consistent with
resource contention but is insufficient to identify a particular component as the bottleneck. Do not conclude Redis or
JWT validation alone caused it without correlated profiles/exporter data. Next: isolate generators, apply production
CPU/heap limits, compare accepted vs rejected work, profile Redis/index work and authentication, compile selector
indexes if CPU evidence supports it, then shard independent domains. Capacity numbers
in [capacity planning](capacity-planning.md) remain explicitly hypothetical inputs.

For the 100k–10M stages, provision multiple dedicated load generators, independently budgeted Redis domains/replicas and
enough app/network capacity according to **measured** per-pod/per-shard limits. Follow the >=2min warmup,>=5min repeated
stages and>=1h soak, exporter and correctness procedure in [benchmark instructions](../benchmarks/README.md). A single
global hash slot cannot be scaled by adding unrelated shards.
