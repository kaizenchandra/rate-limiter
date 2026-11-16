# Distributed rate limiter — Java 21

Runnable Spring Boot 3.5.16 application, atomic Redis Token Bucket, PostgreSQL policy administration, JWT security,
native HTTP enforcement and optional prepaid leases. **This is a tested implementation baseline, not a certified
million-RPS service.** See [verification](docs/benchmark-results.md) for actual evidence and outstanding production
gates.

Read the deliverables in order:

1. [Requirements, assumptions and acceptance](docs/requirements.md)
2. [Architecture, algorithms and decisions](docs/architecture.md), [capacity planning](docs/capacity-planning.md), [ADRs](docs/adrs.md)
3. [Low-level design](docs/low-level-design.md), [OpenAPI](docs/openapi.yaml)
4. [Source](src/main/java/com/example/ratelimiter), [Redis script](src/main/resources/redis/consume.lua), [schema](src/main/resources/db/migration/V1__policies.sql)
5. [Executable tests](src/test/java/com/example/ratelimiter), [test matrix](docs/testing.md)
6. [Docker Compose](docker-compose.yml), [Helm chart](helm), [observability](observability)
7. [Load tests](benchmarks), [methodology and evidence](docs/benchmark-results.md)
8. [Operations and deployment](docs/runbook.md), [failure analysis](docs/failure-analysis.md), [architecture review](docs/review.md)

## Quick start

Requires Java 21, Maven >=3.6.3, Docker Compose v2, Python 3 and curl. The local Keycloak realm contains a privileged
demo service account; use it only for local development.

```sh
cd rate-limiter # from the repository root
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; set your JDK 21 path elsewhere
mvn -B -ntp verify
mvn -B -ntp -Pintegration verify # real Redis/Postgres; fails if Docker is missing
cp .env.example .env # replace DATABASE_PASSWORD with a local randomly generated password
# Alternatively export DATABASE_PASSWORD=$(openssl rand -hex 24)
docker compose up --build -d --remove-orphans --wait
bash tools/smoke.sh
```

The smoke script creates a unique two-token policy, asserts 200, 200, 429 plus Retry-After, checks readiness,
and disables the test policy after success. It can be repeated on the demo stack; other enabled policies may
constrain its requests and cause an assertion failure. Obtain a fresh demo token with `bash tools/token.sh`; valid for
one hour. Readiness is DOWN until a policy exists. Admin endpoints remain
available to bootstrap policies.

For host development, start `docker compose up -d postgres redis keycloak`, set `DATABASE_PASSWORD`,
`JWT_ISSUER=http://localhost:9000/realms/rate-limiter`,
`JWT_JWK_SET_URI=http://localhost:9000/realms/rate-limiter/protocol/openid-connect/certs`, then `mvn spring-boot:run`.
Prometheus
requires a token with `metrics` scope. Liveness is independent of Redis; readiness checks Redis, PostgreSQL, and policy
snapshot availability.

## API examples

```sh
TOKEN=$(bash tools/token.sh)
curl -i http://127.0.0.1:8080/protected/demo -H "Authorization: Bearer $TOKEN"
curl -i http://127.0.0.1:8080/api/v1/rate-limits/evaluate   -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'   --data '{"endpoint":"/protected/demo","cost":1}'
curl http://127.0.0.1:8080/api/v1/rate-limits/effective?endpoint=/protected/demo -H "Authorization: Bearer $TOKEN"
curl http://127.0.0.1:8080/api/v1/policies -H "Authorization: Bearer $TOKEN"
```

The evaluation request intentionally has no tenant/user/API-key fields. They come from signed JWT claims. Unknown fields
are rejected. `/protected/demo` demonstrates embedded enforcement; an application integrating the engine must use
canonical trusted route identifiers and server-computed costs. The service API should be called only by trusted gateways
with `limiter.evaluate` scope.

## Guarantees and limits

* Healthy single-primary Redis, one hash slot, no eviction/corruption/OOM: one Lua invocation checks all <=16 applicable
  buckets before deducting from any. Conjunctive rejection does not consume tokens in other buckets. Redis Lua isolation
  is not rollback on runtime error.
* One coordination domain is one hash slot. A shared exact global rule prevents sharding that domain across primaries.
  The supplied application does **not** implement a distributed transaction across shards or a global multi-region
  allocator.
* Redis asynchronous failover can roll back deductions. No strict guarantee survives state loss, administrator resets or
  fail-open. No automatic application retry of ambiguous operations.
* Polling propagates policy changes normally in ~1 second, with a five-second maximum snapshot age before refusal.
  In-flight operations and stale pods can use old policy until that boundary. A newer consumption revision fences older
  Redis evaluations. Updates are not globally instantaneous.
* Optional leasing is disabled by default; only single-rule cost-one requests qualify. Up to 16 prepaid tokens are
  cached for <=100ms, <=10,000 local leases. Leases trade temporal smoothness and utilization for fewer Redis decisions;
  multi-rule enforcement always uses Redis directly.
* Redis tracks at most 100,000 bucket entries per domain; local policy catalog <=10,000 policies. These conservative
  bounds must be capacity-tested before changing. Saturation refuses new identities (or explicitly fails open); it never
  evicts quota state.
* PostgreSQL is not on the evaluation path. The resolver currently scans the bounded catalog; it is intentionally not
  represented as proven suitable for 10,000 policies at million RPS. Index compilation is a measured future
  optimization.

Dependency versions come from the Spring Boot
BOM. [Official compatibility](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Redis scripting atomicity](https://redis.io/docs/latest/develop/interact/programmability/eval-intro/), [Cluster constraints](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/), [Redis release index](https://download.redis.io/releases/).
Pin image digests and scan the resolved dependency tree before release.

## Local Keycloak

Compose imports [the realm](docker/keycloak/realm.json) on first startup and stores Keycloak data in
`keycloak-data`. The demo confidential client `rate-limiter-demo` uses `local-demo-secret` with the
client-credentials grant. Its tokens carry audience `rate-limiter`, scopes `limiter.admin`,
`limiter.evaluate`, `metrics`, and trusted claims `tenant_id=demo`, `tier=FREE`.
The subject is the Keycloak service-account ID. No browser login or password grant is required.

The public issuer is `http://localhost:9000/realms/rate-limiter`; containers fetch signing keys through
`http://keycloak:8080/realms/rate-limiter/protocol/openid-connect/certs`.
Host runs use the localhost URL for both. Signature, issuer, audience and expiry validation remain enabled.
Realm import skips an existing realm; changes to the JSON require updating that realm or recreating only
the disposable Keycloak volume. Production deployments need a managed Keycloak database, HTTPS,
separate clients with minimal scopes, and rotated credentials.

The legacy `tools/DevIssuer.java` and `tools/make-test-tokens.py` remain available for isolated
many-identity benchmarks only; they are not started by Compose.

If token requests return 404, an obsolete `issuer` container may still own port 9000. Run
`docker compose up --build -d --remove-orphans --wait` to replace it with Keycloak without deleting database volumes.
Use `KEYCLOAK_URL` to override the token server and `BASE_URL` to override the application URL.
