# Master Prompt: Design and Develop a Production-Grade Distributed Rate Limiter Using Java 21 and Spring Boot

## 1. Role and Expertise

Act as a **Principal Software Engineer, Distinguished Engineer, Distributed Systems Architect, Java Performance
Engineer, and Site Reliability Engineer** with 15+ years of experience designing and operating high-throughput,
low-latency, fault-tolerant enterprise systems.

Your expertise includes:

- Java 21, Spring Boot 3.x or a newer verified compatible version
- Distributed systems, concurrency, and network programming
- Redis, Redis Cluster, Lua scripting, and atomic operations
- Apache Kafka and asynchronous event processing
- Kubernetes, Docker, AWS, and GCP
- Spring Security, API Gateway, and microservices
- Prometheus, Grafana, Micrometer, and OpenTelemetry
- JUnit 5, Testcontainers, JMH, Gatling, and k6
- Horizontal scalability, reliability engineering, and capacity planning

You are responsible for **designing, developing, testing, benchmarking, documenting, and preparing an end-to-end
Distributed Rate Limiter for production deployment**.

Do not stop at high-level architecture, pseudocode, or illustrative snippets. Deliver a working, maintainable, testable,
deployable solution with complete source code and operational documentation.

Apply **DDD, SOLID, Clean Architecture, and Hexagonal Architecture pragmatically**, without introducing unnecessary
layers or abstractions.

---

## 2. Primary Objective

Design and implement a **globally distributed, horizontally scalable rate-limiting system** that can support millions of
API requests per second across multiple application instances, Kubernetes pods, and geographic regions.

The system must protect APIs against excessive traffic while enforcing configurable limits at multiple levels.

Support:

1. Per-user rate limiting
2. Per-IP rate limiting
3. Per-API-key rate limiting
4. Per-tenant rate limiting
5. Per-endpoint rate limiting
6. Global rate limiting
7. Tier-based rate limiting (Free, Standard, Premium, Enterprise)
8. Burst traffic control
9. Dynamic rate-limit configuration
10. Distributed quota enforcement across multiple instances

### Performance objectives

Treat the following as design targets requiring validation, not guaranteed benchmark results.

| Metric                      | Target                                                        |
|-----------------------------|---------------------------------------------------------------|
| Aggregate throughput        | 1M requests/second initially; scale-out design toward 5M–10M+ |
| Rate-limit decision latency | p99 below 5 ms within a region                                |
| Availability                | 99.99% target                                                 |
| Horizontal scalability      | Multiple application instances and Redis shards               |
| Concurrent clients          | Millions of unique rate-limit identities                      |
| Decision consistency        | Explicitly defined per policy and deployment mode             |
| Burst handling              | Configurable token capacity and refill rate                   |
| Deployment                  | Kubernetes, cloud-native                                      |
| Observability               | Metrics, logs, distributed tracing                            |
| Configuration changes       | Without application redeployment                              |

**Important:** Distinguish API request throughput from rate-limit decision throughput. Define the benchmark scope,
workload distribution, network boundaries, hardware assumptions, and latency measurement points.

If achieving these goals requires a hybrid architecture, explain why and implement the justified approach.

---

## 3. Engineering Principles

Follow these rules throughout the implementation:

- Prefer correctness, reliability, and maintainability over unnecessary complexity.
- Use actual compatible dependency versions, verified against official documentation.
- Never invent performance results, test results, or benchmark evidence.
- Clearly distinguish implemented functionality, proposed extensions, and unverified assumptions.
- Prefer bounded memory, bounded queues, bounded retries, and explicit timeouts.
- Protect against race conditions, lost updates, clock skew, hot keys, cache stampedes, and Redis failures.
- Avoid blocking critical request-processing paths unnecessarily.
- Keep authentication, authorization, configuration validation, and secrets management explicit.
- Ensure every important architectural decision has a rationale and documented trade-offs.
- Do not introduce Kafka, CQRS, service discovery, or additional microservices unless there is a concrete requirement.
- Avoid claiming strict global consistency when the architecture provides only approximate or eventual enforcement.
- Make routine implementation choices independently, and proceed with a coherent set of assumptions.

---

# PART A — SYSTEM DESIGN

## 4. Functional Requirements

Define and implement the following capabilities.

### 4.1 Rate-limiting algorithms

Evaluate and compare:

- Fixed Window Counter
- Sliding Window Log
- Sliding Window Counter
- Token Bucket
- Leaky Bucket
- Generic Cell Rate Algorithm (GCRA)

For each algorithm explain:

1. How it works
2. Time complexity
3. Space complexity
4. Accuracy
5. Burst behavior
6. Memory consumption
7. Distributed implementation challenges
8. Advantages and disadvantages
9. Suitable production use cases

Select **Token Bucket as the default candidate**, subject to architectural evaluation.

Use another algorithm where it offers measurable advantages.

### 4.2 Rate-limit policies

Support hierarchical policies such as:

- Global: 1,000,000 requests/second
- Tenant: 100,000 requests/second
- User: 1,000 requests/second
- API key: 500 requests/second
- Endpoint: 100 requests/second

These are illustrative configuration values, not measured capacity.

Define whether multiple applicable limits are enforced conjunctively.

If hierarchical enforcement is required, ensure an accepted request consumes quota consistently across all applicable
policies. A rejected request must not incorrectly consume quota from unrelated policies.

Explain when multi-key atomicity is possible and when it becomes impractical across Redis Cluster hash slots.

### 4.3 Dynamic policy management

Provide APIs to:

- Create rate-limit policies
- Update rate-limit policies
- Delete or disable policies
- Retrieve effective policies
- Retrieve policy versions
- Inspect rate-limit status

Support policy inheritance, tenant overrides, validation, and propagation across instances.

Describe how conflicting rules are resolved deterministically.

---

## 5. Non-Functional Requirements

Evaluate:

- Throughput and p50/p95/p99 latency
- Availability and fault tolerance
- Horizontal scalability
- Fairness across users and tenants
- Consistency versus performance
- Redis memory utilization
- Network overhead
- Regional failover
- Graceful degradation
- Operational simplicity
- Security and multi-tenant isolation
- Infrastructure cost

Explain what guarantees the system can and cannot provide.

---

## 6. High-Level Architecture (HLD)

Design the complete architecture.

Consider the following logical components:

1. API Gateway or rate-limiting enforcement layer
2. Rate Limiter Engine
3. Policy Resolution Engine
4. Local Configuration Cache
5. Local Token Bucket or Quota Lease Cache
6. Distributed Coordination Layer
7. Redis Cluster
8. Policy Management API
9. PostgreSQL for durable policy storage
10. Observability infrastructure

Introduce Kafka only if justified for asynchronous configuration propagation, audit events, or another concrete
requirement.

Provide a Mermaid architecture diagram showing:

- Client requests
- Gateway enforcement
- Policy lookup
- Local and distributed quota decisions
- Redis communication
- Policy administration
- Persistence
- Metrics and tracing

Explain each component's responsibilities and the critical request path.

Compare three deployment options:

**Option A — Centralized rate-limiter service**

Every request invokes a dedicated rate-limiter service.

**Option B — Embedded rate limiter**

Each API Gateway instance enforces policies locally while coordinating through Redis.

**Option C — Hybrid rate limiter**

A local fast path uses bounded token/quota leases with distributed coordination.

Evaluate all three and select the architecture that best meets the defined targets.

Explicitly analyze the trade-off between globally strict quotas and locally leased quotas.

---

## 7. Capacity Planning

Provide mathematical capacity estimates for:

- 1 million requests/second
- 5 million requests/second
- 10 million requests/second

Calculate:

1. Number of application instances required
2. Redis command volume
3. Redis shard requirements
4. Network bandwidth
5. Memory per active identity
6. Total Redis memory
7. Connection pool requirements
8. CPU utilization assumptions
9. Hot-key traffic concentration
10. Multi-region overhead
11. Infrastructure cost drivers

State every assumption.

Include formulas and sample calculations.

Do not invent vendor throughput numbers.

Explain why a single Redis instance or one global counter can become a bottleneck.

Analyze skewed traffic patterns such as Zipfian distributions and large tenants generating disproportionate loads.

---

# PART B — LOW-LEVEL DESIGN

## 8. Domain and Component Design

Create a detailed Low-Level Design.

Suggested domain concepts:

- RateLimitPolicy
- RateLimitRule
- RateLimitIdentity
- TokenBucket
- RateLimitDecision
- RateLimitContext
- PolicyResolver
- RateLimitEngine
- DistributedTokenStore
- LocalQuotaManager
- PolicyRepository
- RateLimitMetrics

Apply separation of concerns.

Define clear interfaces for:

- Policy management
- Identity resolution
- Rate-limit evaluation
- Distributed state management
- Observability

Use Java 21 records, sealed interfaces, and other modern language features where appropriate.

Do not introduce inheritance merely to demonstrate design patterns.

Provide:

- UML class diagram
- Sequence diagram
- State transitions
- Data model
- API contracts
- Error handling strategy
- Concurrency model

---

## 9. Distributed Token Bucket Implementation

Implement an atomic Redis-backed Token Bucket.

Cover:

1. Bucket capacity
2. Token refill rate
3. Elapsed time calculation
4. Token consumption
5. Remaining quota
6. Retry-after calculation
7. Expiration and cleanup
8. Fractional token precision
9. Integer overflow protection
10. Clock skew

Use Redis Lua scripting or an equivalently atomic Redis-side mechanism.

Prefer server-side time where justified.

Explain the Redis data structures and atomicity guarantees.

Ensure concurrent requests from multiple Spring Boot instances do not overspend the same bucket under normal operation.

Design the script for efficiency and bounded resource consumption.

Explain Redis Cluster hash-slot constraints.

Test bucket behavior under high concurrency, script failures, failover, and state expiration.

Explicitly analyze whether Redis replication and failover could permit quota loss or overspending.

---

## 10. Local Quota Leasing and Optimization

Evaluate a hybrid optimization for reducing Redis operations.

For example:

- Redis grants a bounded token lease to a gateway instance.
- The instance serves requests locally using thread-safe counters.
- The instance renews its lease before exhaustion.
- Unused leased tokens expire or are reclaimed conservatively.

Address:

- Atomic lease allocation
- Maximum permissible overshoot
- Lease expiration
- Pod crashes
- Gateway scaling
- Lease renewal races
- Clock uncertainty
- Rebalancing
- Fairness between instances
- Underutilized quota
- Policy updates during active leases

Derive an explicit upper bound on over-admission where possible.

Do not claim exact global limits when using approximate leases.

Support disabling local leasing for policies requiring stricter enforcement.

---

# PART C — IMPLEMENTATION

## 11. Technology Stack

Use the following stack unless a justified alternative is needed.

| Layer               | Technology                                             |
|---------------------|--------------------------------------------------------|
| Language            | Java 21                                                |
| Framework           | Spring Boot, compatible stable release                 |
| Build               | Maven                                                  |
| API                 | Spring Web MVC or WebFlux, selected with justification |
| Redis               | Redis Cluster                                          |
| Redis Client        | Lettuce                                                |
| Persistence         | PostgreSQL                                             |
| Database migration  | Flyway                                                 |
| Security            | Spring Security                                        |
| Metrics             | Micrometer                                             |
| Tracing             | OpenTelemetry                                          |
| Testing             | JUnit 5, Mockito, Testcontainers                       |
| Performance testing | k6 or Gatling                                          |
| Deployment          | Docker, Kubernetes, Helm                               |
| Monitoring          | Prometheus, Grafana                                    |

Verify compatibility before selecting versions.

Explain the impact of blocking versus non-blocking Redis access.

Consider Java 21 virtual threads where they provide a demonstrated benefit, and explain when they are not appropriate.

Avoid assuming virtual threads automatically increase throughput.

---

## 12. Implement the Complete Application

Develop a runnable project containing:

### Core engine

- Rate-limit evaluation
- Token Bucket algorithm
- Hierarchical policy resolution
- Redis-backed atomic enforcement
- Optional local quota leasing
- Fail-open/fail-closed policy support

### Spring Boot integration

- REST API
- Servlet Filter or WebFlux WebFilter, depending on the selected stack
- Security integration
- Identity extraction
- Standard rate-limit response headers
- HTTP 429 handling
- Configurable policy selection

### Management functionality

- Create policy
- Update policy
- Delete policy
- List policies
- Evaluate effective policy
- Inspect rate-limit decisions

### Persistence

- PostgreSQL schema
- Flyway migrations
- Repository implementation
- Policy versioning
- Cache invalidation

### Infrastructure

- Redis integration
- Docker Compose
- Redis Cluster configuration for representative testing
- Kubernetes manifests or Helm chart
- Health checks
- Readiness and liveness probes
- Graceful shutdown

### Documentation

- README
- Architecture Decision Records
- API specification
- Operational runbook
- Deployment instructions
- Benchmark instructions

Produce actual source files, not only a project outline.

---

## 13. Project Structure

Start with a focused modular structure such as:

```text
distributed-rate-limiter/
├── pom.xml
├── README.md
├── docs/
│   ├── architecture.md
│   ├── capacity-planning.md
│   ├── benchmark-results.md
│   └── runbook.md
├── src/
│   ├── main/
│   │   ├── java/com/example/ratelimiter/
│   │   │   ├── api/
│   │   │   ├── application/
│   │   │   ├── domain/
│   │   │   ├── infrastructure/
│   │   │   │   ├── redis/
│   │   │   │   └── persistence/
│   │   │   ├── configuration/
│   │   │   └── security/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── db/migration/
│   │       └── redis/
│   └── test/
│       └── java/
├── benchmarks/
│   ├── k6/
│   └── results/
├── docker/
├── helm/
└── .github/workflows/
```

Adapt this structure when necessary.

Prefer a modular monolith or embedded enforcement component before introducing independently deployed microservices.

Explain package boundaries and dependencies.

---

# PART D — API CONTRACTS

## 14. REST API Design

Implement a rate-limit evaluation API.

Example:

`POST /api/v1/rate-limits/evaluate`

Request:

```json
{
  "tenantId": "tenant-123",
  "userId": "user-456",
  "apiKeyId": "key-789",
  "endpoint": "/api/payments",
  "cost": 1
}
```

Response:

```json
{
  "allowed": true,
  "remainingTokens": 42,
  "retryAfterMs": 0,
  "policyId": "premium-policy"
}
```

Also provide management APIs for policy CRUD operations.

Define:

- Authentication and authorization
- Request validation
- Idempotency where necessary
- API versioning
- HTTP status codes
- Error responses
- Request timeouts
- Rate-limit headers

Never trust user-supplied tenant IDs, identities, or API-key ownership without verification.

Distinguish administrative APIs from the high-throughput evaluation path.

For native gateway enforcement, demonstrate HTTP 429 responses with appropriate `Retry-After` and documented rate-limit
headers.

---

# PART E — DISTRIBUTED SYSTEMS CORRECTNESS

## 15. Concurrency, Consistency, and Failure Handling

Analyze these production scenarios individually:

1. Two requests concurrently consume the last token.
2. One thousand application instances update the same identity.
3. Redis becomes temporarily unavailable.
4. Redis primary fails during token consumption.
5. Redis failover causes state rollback.
6. A network timeout occurs after Redis processed a request.
7. An application instance crashes holding a local quota lease.
8. Redis Cluster resharding is in progress.
9. A hot tenant overwhelms a Redis shard.
10. A configuration update changes a quota while requests are in flight.
11. Policies expire during active requests.
12. Two geographic regions enforce the same quota.
13. Local clocks disagree.
14. Retry storms occur after a dependency recovers.
15. A client attempts to bypass the limiter using spoofed identities.
16. An attacker generates millions of unique keys.
17. A partial deployment runs mixed application versions.
18. A policy is deleted while its bucket state still exists.
19. Requests reach Redis but application-side responses time out.
20. Kubernetes abruptly terminates a gateway pod.

For each scenario provide:

- Failure mechanism
- User-visible impact
- Detection signals
- Prevention or mitigation
- Recovery approach
- Test strategy
- Residual risk

Define explicit consistency guarantees for single-key enforcement, hierarchical policies, and multi-region quotas.

---

## 16. Fail-Open Versus Fail-Closed

Implement configurable failure behavior.

Examples:

- Login and security-sensitive endpoints: generally fail closed or use a bounded emergency allowance.
- Critical internal business APIs: policy-based choice.
- Public informational endpoints: potentially fail open with safeguards.

Explain availability, abuse, revenue, and security implications.

Avoid treating fail-open as universally safe or fail-closed as universally correct.

---

## 17. Multi-Region Design

Compare:

1. Region-local quotas
2. Statically partitioned global quotas
3. Dynamically allocated regional quota leases
4. Centralized global coordination

Evaluate:

- Cross-region latency
- Regional isolation
- Failover
- Quota utilization
- Global accuracy
- Network partitions
- Disaster recovery

Clearly distinguish **strict global rate limiting** from approximate regional enforcement.

Recommend a realistic multi-region approach for millions of requests per second.

---

# PART F — SECURITY AND RELIABILITY

## 18. Security Requirements

Implement or document:

- OAuth2/JWT authentication integration
- RBAC for policy administration
- Trusted API-key identification
- Trusted proxy configuration for client IP extraction
- Redis authentication and TLS
- Secure service-to-service communication
- Secrets through Kubernetes Secrets or a secrets manager
- Input validation
- Multi-tenant isolation
- Audit logging of policy changes
- Protection against high-cardinality identity attacks
- Request payload limits
- Safe metrics labeling

Do not log API keys, secrets, raw credentials, or sensitive user information.

---

## 19. Operational Reliability

Implement:

- Explicit Redis timeouts
- Bounded retries
- Circuit breaker where justified
- Connection pool limits
- Backpressure
- Graceful shutdown
- Health probes
- Graceful degradation
- Dependency failure metrics
- Alerting
- Deployment rollback procedures

Explain how retrying an ambiguous token-consumption operation can result in double consumption.

Provide a safe policy for unknown outcomes rather than assuming all operations are idempotent.

---

# PART G — TESTING

## 20. Comprehensive Test Strategy

Write executable tests covering:

### Unit tests

- Initial bucket state
- Token consumption
- Token exhaustion
- Refilling
- Burst capacity
- Retry-after
- Fractional refill
- Invalid configuration
- Boundary values
- Integer overflow
- Policy precedence

### Integration tests

Use Testcontainers where appropriate for:

- Redis atomic evaluation
- PostgreSQL persistence
- Policy CRUD
- Cache invalidation
- Security enforcement
- HTTP 429 behavior

### Concurrency tests

Verify:

- Simultaneous requests
- Multiple application instances
- Same-key contention
- Different-key parallelism
- No overspending in a healthy single-primary Redis configuration
- Local quota lease bounds

### Failure tests

Simulate:

- Redis timeout
- Redis restart
- Connection refusal
- Cluster failover
- Expired lease
- Duplicate retries
- Policy changes
- Instance crashes

### Property-based tests

Where useful, verify invariants such as:

- Token balance never exceeds capacity.
- Token balance never becomes negative.
- Refill is monotonic with elapsed time under the defined clock model.
- No more than the configured quota is admitted under specified strict-enforcement assumptions.
- Rejected hierarchical evaluations have the documented quota-consumption behavior.

Do not substitute mocks for tests intended to prove Redis atomicity or real network behavior.

---

# PART H — PERFORMANCE ENGINEERING

## 21. Benchmarking and Load Testing

Create executable load-testing scripts.

Evaluate:

1. 10,000 requests/second
2. 100,000 requests/second
3. 500,000 requests/second
4. 1 million requests/second
5. 5 million requests/second
6. 10 million requests/second

These are test stages, not claims that the available environment can reach them.

Cover:

- Uniform identity distribution
- Hot-key distribution
- Zipfian traffic
- Bursty traffic
- Read-heavy policy access
- High-cardinality keys
- Hierarchical policies
- Redis degradation
- Pod autoscaling
- Multi-region latency

Compare:

- Pure Redis Token Bucket
- Local-only Token Bucket
- Hybrid Token Bucket with quota leasing

Measure:

- Achieved RPS
- Offered RPS
- Allowed and rejected requests
- p50/p95/p99 decision latency
- HTTP end-to-end latency
- Error rate
- Redis operations/second
- Redis CPU and memory
- Network utilization
- Application CPU and heap
- JVM GC pauses
- Lock contention
- Quota overshoot
- Fairness and quota utilization

Provide benchmark scripts, deployment topology, exact commands, and results tables.

If the available environment cannot execute a target-scale benchmark, clearly state **Not tested** and provide the
required infrastructure and reproduction procedure.

Never fabricate measurements.

---

## 22. Performance Optimization

Analyze and implement justified optimizations, such as:

- Local configuration caches
- Atomic Redis scripts
- Reduced serialization overhead
- Efficient key formats
- Bounded key cardinality
- Redis pipelining where semantic constraints allow
- Connection reuse
- Local quota leasing
- Contention reduction
- Non-blocking I/O where beneficial
- Redis Cluster sharding
- Hot-key mitigation
- Reduced object allocation
- JVM and GC tuning based on evidence
- Java 21 concurrency primitives

For each optimization explain:

1. Existing bottleneck
2. Proposed change
3. Expected benefit
4. Correctness trade-off
5. Benchmark method
6. Observed result, if actually measured

Avoid premature optimization.

---

# PART I — DEPLOYMENT AND OBSERVABILITY

## 23. Docker and Kubernetes

Provide:

- Production-oriented Dockerfile
- Docker Compose for local development
- Helm chart or Kubernetes manifests
- Deployment
- Service
- ConfigMap
- Secret references
- Horizontal Pod Autoscaler
- PodDisruptionBudget
- Resource requests and limits
- Readiness and liveness probes
- Rolling deployment strategy

Do not put real secrets in source control.

Explain scaling behavior and resource sizing assumptions.

Account for Redis topology, connection limits, and Kubernetes termination grace periods.

---

## 24. Monitoring and Alerting

Provide Prometheus metrics for:

- Rate-limit decisions
- Accepted requests
- Rejected requests
- Decision latency
- Redis latency
- Redis failures
- Lease allocation
- Lease exhaustion
- Policy cache hit rate
- Fallback decisions
- Timeout events
- Configuration propagation failures

Avoid high-cardinality metric labels such as user IDs and API keys.

Provide a Grafana dashboard specification.

Recommend SLOs and alert thresholds with explicit justification.

Include structured logging and distributed tracing.

---

# PART J — DELIVERABLES

## 25. Required Outputs

Deliver the complete solution in the following order.

### Deliverable 1 — Requirements and Assumptions

- Functional requirements
- Non-functional requirements
- Explicit assumptions
- Consistency guarantees
- Acceptance criteria

### Deliverable 2 — Architecture

- Architecture diagram
- Request flow
- Technology selection
- Capacity planning
- Multi-region approach
- Architecture Decision Records

### Deliverable 3 — Low-Level Design

- Domain model
- Class diagram
- Sequence diagram
- Concurrency model
- Redis key design
- Database schema
- API contracts

### Deliverable 4 — Working Source Code

- Complete Java 21 project
- Spring Boot configuration
- Token Bucket implementation
- Atomic Redis script
- Policy management
- API enforcement
- Database integration
- Security configuration
- Metrics and tracing integration

### Deliverable 5 — Automated Tests

- Unit tests
- Integration tests
- Concurrency tests
- Failure tests
- Security tests

### Deliverable 6 — Infrastructure

- Dockerfile
- Docker Compose
- Kubernetes or Helm configuration
- Observability configuration

### Deliverable 7 — Performance Testing

- Load test scripts
- Benchmark setup
- Measurement methodology
- Actual results where executable
- Bottleneck analysis
- Scalability projections clearly labeled as estimates

### Deliverable 8 — Documentation

- README
- Local setup
- API examples
- Deployment guide
- Troubleshooting
- Failure recovery runbook
- Production readiness checklist
- Known limitations

---

# PART K — EXECUTION INSTRUCTIONS

## 26. Development Workflow

Execute the work in the following phases.

**Phase 1: Requirements and architecture**

Identify the main scalability and consistency constraints. Compare possible architectures, select one, and document the
trade-offs.

**Phase 2: Implement the minimal correct distributed rate limiter**

Implement Redis-backed atomic Token Bucket enforcement, Spring Boot integration, configuration, and meaningful tests.

**Phase 3: Add policy management and security**

Introduce PostgreSQL-backed policies, dynamic configuration, trusted identity resolution, and administration
authorization.

**Phase 4: Add scale optimizations**

Implement optional local quota leasing or other optimizations only after defining and testing the correctness
guarantees.

**Phase 5: Reliability and observability**

Implement failure handling, metrics, tracing, health probes, and operational safeguards.

**Phase 6: Deployment and verification**

Implement Docker, Kubernetes, CI checks, integration tests, and reproducible benchmark scripts.

**Phase 7: Review and hardening**

Conduct an architecture, code quality, security, concurrency, and performance review. Fix significant issues discovered.

For each phase:

- Implement the required changes rather than merely describing them.
- Keep the project buildable.
- Run available checks.
- Correct implementation defects.
- Preserve already working functionality.
- State what has actually been verified.
- Identify remaining risks and limitations.

If repository or file tools are available, create and modify the project files directly.

If file tools are unavailable, provide complete, logically organized source files with their paths. Avoid placeholders,
unexplained omissions, and truncated implementations.

Do not claim a phase is complete if mandatory parts have not been implemented.

---

## 27. Mandatory Architecture Review Questions

Before finalizing, answer:

1. Why is Token Bucket preferable to Fixed Window for this workload?
2. How does Redis provide atomic token consumption?
3. What happens when multiple instances consume the final token?
4. Can Redis failover cause quota overshoot?
5. How can the system support 10 million requests per second?
6. When would Redis become the bottleneck?
7. How can local quotas reduce Redis traffic?
8. What consistency guarantees are sacrificed with quota leasing?
9. How do you prevent hot-key bottlenecks?
10. How do you enforce several policies without partial token consumption?
11. How do you handle policy changes during active requests?
12. What happens when Redis is unavailable?
13. How do you safely handle ambiguous network timeouts?
14. How do you enforce limits across multiple regions?
15. How do you prevent malicious high-cardinality key creation?
16. How do you choose between Web MVC, WebFlux, and virtual threads?
17. How do you prevent excessive Redis connections during autoscaling?
18. How do you prove correctness under concurrency?
19. How do you validate million-RPS throughput scientifically?
20. What conditions must be satisfied before production deployment?

---

## 28. Definition of Done

The solution is considered complete only when:

- The Java 21 application builds successfully.
- The core Token Bucket implementation is correct under its documented assumptions.
- Atomic Redis enforcement is verified with integration and concurrency tests.
- Rate-limit policies can be configured and managed securely.
- HTTP 429 behavior is demonstrated.
- Redis and application failure behavior is documented and tested.
- Deployment configuration is provided.
- Metrics are exposed.
- Load tests are reproducible.
- Scalability claims are supported by measurements or clearly labeled estimates.
- Remaining risks and limitations are documented.
- The source code and documentation are consistent.

**A functional implementation does not automatically imply that the million-RPS performance target has been achieved.
That target requires separate load-test evidence.**

---

# FINAL INSTRUCTIONS

Approach this as a real enterprise engineering project, not a theoretical system design exercise.

Prioritize the following outcomes:

1. **Correctness:** No unintended quota overspending under the explicitly supported consistency model.
2. **Scalability:** An architecture that can scale toward millions of decisions per second through measurable horizontal
   scaling.
3. **Low latency:** Minimal overhead on the API request critical path.
4. **Resilience:** Defined and tested behavior during Redis, application, and network failures.
5. **Maintainability:** Clean, focused Java 21 and Spring Boot implementation.
6. **Security:** Trusted identity resolution, administrative access control, and tenant isolation.
7. **Observability:** Enough telemetry to investigate throttling behavior and production incidents.
8. **Reproducibility:** Automated builds, tests, deployment instructions, and benchmarks.

Begin with a concise architectural assessment and a recommended implementation strategy.

Then execute the development phases and produce the actual deliverables.

At completion, report:

- What was implemented
- Why the architecture was selected
- Tests and verification actually performed
- Achieved performance, if measured
- Known limitations
- Remaining production risks

**Do not stop after presenting the design. Build the system end to end.**