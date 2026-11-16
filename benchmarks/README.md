# Reproducible performance testing

These scripts offer load; they do not demonstrate that the target is achievable.
Read [results and limitations](../docs/benchmark-results.md) before interpreting any number.

## Local HTTP preparation

Use an isolated disposable deployment from README. For distinct benchmark identities, stop Compose Keycloak and run the
legacy issuer explicitly with
`java tools/DevIssuer.java` (local use only). Configure the app with `JWT_ISSUER=http://localhost:9000`
and `JWT_JWK_SET_URI=http://host.docker.internal:9000/jwks` (host app: `http://localhost:9000/jwks`).
For a single Keycloak identity, use `bash tools/token.sh` and a one-element token JSON file instead. The token generator
supports
distinct signed subjects; JWT claims are never spoofed in request JSON.

```sh
python3 tools/make-test-tokens.py --count 1000 --output /tmp/limiter-load-tokens.json
TOKEN=$(curl -fsS http://127.0.0.1:9000/tokens | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
curl -fsS http://127.0.0.1:8080/api/v1/policies   -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'   --data-binary @benchmarks/policies/single.json
k6 run -e BASE_URL=http://127.0.0.1:8080 -e ENDPOINT=/benchmark   -e RATE=10000 -e DURATION=60s -e VUS=200 -e MAX_VUS=1000   -e TOKENS_FILE=/tmp/limiter-load-tokens.json -e DISTRIBUTION=uniform   -e RESULT=benchmarks/results/uniform-10000.json benchmarks/k6/evaluate.js
```

The example policy overrides `user` for tenant demo on `/benchmark`. On an empty database it is the only applicable rule
there. Do not apply benchmark policies to production. If it already exists, inspect its version and deliberately PUT
with the expected If-Match rather than blindly recreating or overwriting. For the five-rule conjunction, POST each
object in `benchmarks/policies/hierarchy.json` to an otherwise empty policy catalog; the file is a fixture array,
**not** a bulk-API request body. Disable the single fixture before that comparison so exactly five rules apply.

`DISTRIBUTION=hot` sends80% to the first signed identity; `uniform` samples all; `zipf` samples a truncated Zipf CDF
with exponent1.1. With one token all distributions collapse to a single hot key. Use thousands/millions of real test
identities for cardinality conclusions. The demo issuer is intentionally simple and not a high-throughput identity
service; generate credentials outside the measured interval, protect the files and refresh tokens before expiry. If the
ephemeral issuer restarts, restart demo clients/apps or wait for JWKS cache refresh since its development key ID is
fixed.

Run all offered-rate stages explicitly:

```sh
export TOKENS_FILE=/tmp/limiter-load-tokens.json
bash benchmarks/run-stages.sh
# Individual stages: RATE=10000,100000,500000,1000000,5000000,10000000
k6 run -e TOKENS_FILE="$TOKENS_FILE" benchmarks/k6/bursts.js
```

The staged runner stops at failed thresholds; do not interpret a failed stage as passed or bypass dropped-arrival
alarms. `VUS`/`MAX_VUS` defaults are local starting values; provision distributed generators rather than assuming one
laptop can emit10M requests/s. To partition offered rate, set RATE=globalTarget/generatorCount per generator, separate
RESULT files, identical policy/workload topology and independent signed identity subsets. Use a controlled k6
operator/distributed runner and collect complete histogram time series; do not average per-generator p99 values.

## Compare algorithms/adapters

```sh
# Uses an already running local Redis, no HTTP path:
mvn -Pbenchmark -Dbenchmark.requests=10000 -Dbenchmark.redis.port=6379 test
cat benchmarks/results/adapter-comparison.csv
```

This compares the actual Java local TokenBucket, actual Redis adapter and actual prepaid LocalQuotaManager on one hot
identity, single thread. It is a short diagnostic without JMH forks, sufficient warmup guarantees, allocation/lock
profiling or confidence intervals. The sub-millisecond local-only batch is particularly unreliable for performance
prediction. It is never a distributed quota implementation. `benchmarks/local-only.js` is an additional per-VU algorithm
demonstration, not a Java or distributed benchmark.

For full-service hybrid comparison, set both deployment `LEASING=true` and fixture policy `lease=true` via an optimistic
update, then repeat the exact same identity distribution/RPS/cost. Only one applicable rule and cost1 qualify;
hierarchical fixtures always use Redis directly. Record grants, misses, calls and admitted requests. Never silently
remove hierarchies to make a leasing result look faster.

## Measurement protocol

1. Record source/image digests, Java/dependency/Redis/Postgres/k6 versions, CPU model/count/limits, memory, GC flags,
   kernel, AZ/region, app and shard counts, policy count/hierarchy depth, identity pool, cost/burst/refill, lease
   settings, RTT/TLS/proxy boundaries and persistence/replica settings.
2. Benchmark engine-only and end-to-end HTTP separately. Engine timer starts before snapshot/resolution and ends after
   decision/fallback; HTTP includes JWT/auth, servlet/filter/serialization, socket/proxy/network and server queues.
   Adapter diagnostic includes Lua/serialization/network but excludes HTTP/auth/resolution. HTTP p99 is not engine p99.
3. Warm JVM, JWK/script caches and connection/topology caches with >=2min representative traffic. Run >=5min steady
   stages after warmup, at least three independent repetitions, plus a >=1h soak. The supplied local10s samples do not
   meet this protocol.
4. Open-model arrivals expose saturation. Keep offered rate, attempted/completed HTTP, allowed/429/errors, dropped
   iterations, achieved throughput including drain time and generator CPU separate. A429 is a decision, not an API
   execution. k6's built-in http_req_failed includes429; custom limiter_errors excludes recognized429.
5. Scrape app/Redis/Postgres/host metrics at 1–10s: histograms, CPU/heap/GC, commands/EVALSHA/internal work, replication
   lag, memory/fragmentation/index cardinality, sockets, network and scheduler/lock profiles (JFR/async-profiler in
   dedicated runs). `INFO commandstats` counts EVALSHA differently from script-internal commands; report both.
6. Compute quota overshoot using accepted cost within sliding windows against C+rΔ, with lease allowance N*16 and policy
   transition boundaries recorded. Use a fixed low refill rate for concurrency proofs. Compare per-tenant/user accepted
   ratios and Jain fairness under equal-weight workloads; do not infer fairness from aggregate RPS. IDs may go to
   bounded private analysis files, never metric labels.
7. Repeat uniform/hot/Zipf, many-policy read-heavy snapshots, valid identity churn, bursts, hierarchy, Redis
   pause/outage/recovery, pod scale/drain and WAN latency scenarios. Chaos must run only in disposable staging. Keep
   clocks synchronized for cross-host event analysis; use monotonic local duration measurements.
8. Saturation analysis: correlate CPU, event-loop delay/Redis RTT, EVAL CPU, queue saturation, bulkhead denial and
   generator drops. Test one change at a time. More VUs can increase queueing without increasing sustainable service
   rate.

Redis exporter and cAdvisor/node exporter are external dependencies for infrastructure metrics; the application only
provides its own Micrometer metrics. Do not claim exported Redis CPU/memory/traffic data were collected when they were
not. Raw k6 summaries and adapter CSV are retained in `results`; private JWT files are not.
