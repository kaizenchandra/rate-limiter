package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ratelimiter.application.*;
import com.example.ratelimiter.domain.*;
import com.example.ratelimiter.infrastructure.redis.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class RedisAtomicTest {
    static GenericContainer<?> container;
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    RedisTokenStore store;
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @BeforeAll
    static void start() {
        int port = Integer.getInteger("redis.port", 0);
        String host = "127.0.0.1";
        if (port == 0) {
            container =
                    new GenericContainer<>(DockerImageName.parse("redis:8.2.11-alpine"))
                            .withExposedPorts(6379);
            container.start();
            port = container.getMappedPort(6379);
            host = container.getHost();
        }
        factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void stop() {
        if (factory != null) factory.destroy();
        if (container != null) container.stop();
    }

    @BeforeEach
    void setup() {
        try (var connection = factory.getConnection()) {
            connection.ping();
        }
        store = new RedisTokenStore(redis, metrics, "test-" + UUID.randomUUID());
    }

    Policy p(String name, long capacity, double rate) {
        return new Policy(
                name,
                name,
                Policy.Dimension.USER,
                "*",
                "*",
                "*",
                capacity,
                rate,
                Policy.FailureMode.CLOSED,
                false,
                true,
                1);
    }

    Context c(String user) {
        return new Context("tenant", user, "key", "127.0.0.1", "FREE", "/demo", 1);
    }

    @Test
    void initialConsumptionExhaustionAndRetry() {
        var ps = List.of(p("u", 2, .001));
        assertEquals(1, store.consume(c("u"), ps, 1, 1).remainingTokens());
        assertTrue(store.consume(c("u"), ps, 1, 1).allowed());
        var no = store.consume(c("u"), ps, 1, 1);
        assertFalse(no.allowed());
        assertTrue(no.retryAfterMs() > 990000);
    }

    @Test
    void rejectsWithoutConsumingOtherPolicies() {
        var a = p("a", 2, .001);
        var b = p("b", 1, .001);
        assertTrue(store.consume(c("u"), List.of(b), 1, 1).allowed());
        assertFalse(store.consume(c("u"), List.of(a, b), 1, 1).allowed());
        assertEquals(1, store.consume(c("u"), List.of(a), 1, 1).remainingTokens());
    }

    @Test
    void fractionalRefillAndBurst() throws Exception {
        var ps = List.of(p("u", 2, 2.5));
        assertTrue(store.consume(c("u"), ps, 1, 2).allowed());
        assertFalse(store.consume(c("u"), ps, 1, 1).allowed());
        Thread.sleep(450);
        assertTrue(store.consume(c("u"), ps, 1, 1).allowed());
    }

    @Test
    void concurrencyAcrossTwoInstancesCannotOverspend() throws Exception {
        var peer = new RedisTokenStore(redis, metrics, "peer-" + UUID.randomUUID());
        // Same coordination domain and distinct client instance.
        String domain = "concurrent-" + UUID.randomUUID();
        store = new RedisTokenStore(redis, metrics, domain);
        peer = new RedisTokenStore(redis, metrics, domain);
        final var other = peer;
        try (var pool = Executors.newFixedThreadPool(32)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                final int n = i;
                calls.add(
                        () ->
                                (n % 2 == 0 ? store : other)
                                        .consume(c("hot"), List.of(p("u", 100, .001)), 1, 1)
                                        .allowed());
            }
            int accepted = 0;
            for (var f : pool.invokeAll(calls)) if (f.get()) accepted++;
            assertEquals(100, accepted);
        }
    }

    @Test
    void differentKeysAndStateExpiry() throws Exception {
        assertTrue(store.consume(c("a"), List.of(p("u", 1, 1000)), 1, 1).allowed());
        assertTrue(store.consume(c("b"), List.of(p("u", 1, 1000)), 1, 1).allowed());
        Thread.sleep(1100);
        assertTrue(store.consume(c("a"), List.of(p("u", 1, 1000)), 1, 1).allowed());
    }

    @Test
    void staleRevisionCannotRefillOrSpend() {
        assertTrue(store.consume(c("u"), List.of(p("u", 10, 1)), 2, 1).allowed());
        assertThrows(
                IllegalStateException.class, () -> store.consume(c("u"), List.of(p("u", 100, 100)), 1, 1));
    }

    @Test
    void costBoundaryAndLargeNumbers() {
        var ps = List.of(p("u", 1000000000, 1000000000));
        assertTrue(store.consume(c("u"), ps, 1, 1000000000).allowed());
        assertFalse(store.consume(c("u"), ps, 1, 1000000001).allowed());
    }

    @Test
    void quotaLeasesDoNotMintTokensUnderConcurrency() throws Exception {
        var manager = new LocalQuotaManager(store, metrics);
        var policy = p("lease", 16, .001);
        try (var pool = Executors.newFixedThreadPool(16)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 64; i++) calls.add(() -> manager.consume(c("u"), policy, 1).allowed());
            long admitted = 0;
            for (var f : pool.invokeAll(calls)) if (f.get()) admitted++;
            assertTrue(admitted <= 16);
            assertTrue(admitted > 0);
        }
    }

    @Test
    void leaseExpiryLosesUnusedTokensConservatively() throws Exception {
        var manager = new LocalQuotaManager(store, metrics);
        var p = p("lease", 16, .001);
        assertTrue(manager.consume(c("u"), p, 1).allowed());
        Thread.sleep(120);
        assertFalse(manager.consume(c("u"), p, 1).allowed());
    }

    @Test
    void randomizedBalancesStayWithinCapacity() {
        var random = new Random(42);
        var ps = List.of(p("u", 100, .001));
        long spent = 0;
        for (int i = 0; i < 200; i++) {
            long cost = random.nextInt(10) + 1;
            var d = store.consume(c("u"), ps, 1, cost);
            if (d.allowed()) spent += cost;
            assertTrue(d.remainingTokens() >= 0 && d.remainingTokens() <= 100);
            assertTrue(spent <= 100);
        }
    }

    @Test
    void policyCapacityDecreaseNeverResetsFullBucket() {
        assertTrue(store.consume(c("u"), List.of(p("u", 10, .001)), 1, 8).allowed());
        assertFalse(store.consume(c("u"), List.of(p("u", 5, .001)), 2, 3).allowed());
    }

    @Test
    void realConnectionRefusalFailsInsteadOfAllowing() {
        var refused = new LettuceConnectionFactory("127.0.0.1", 1);
        refused.afterPropertiesSet();
        try {
            var s = new RedisTokenStore(new StringRedisTemplate(refused), metrics, "refused");
            assertThrows(RuntimeException.class, () -> s.consume(c("u"), List.of(p("u", 1, 1)), 1, 1));
        } finally {
            refused.destroy();
        }
    }

    @Test
    void inspectionDoesNotSpendOrCreateBuckets() {
        var ps = List.of(p("inspect", 2, .001));
        assertEquals(2, store.inspect(c("u"), ps, 1).remainingTokens());
        assertEquals(2, store.inspect(c("u"), ps, 1).remainingTokens());
        assertEquals(1, store.consume(c("u"), ps, 1, 1).remainingTokens());
        assertEquals(1, store.inspect(c("u"), ps, 1).remainingTokens());
    }

    @Test
    void cardinalityGuardRejectsNewBucketsButPreservesOldOnes() {
        String domain = "bounded-" + UUID.randomUUID();
        var bounded = new RedisTokenStore(redis, metrics, domain);
        String index = "rl:{" + domain + "}:identities";
        java.util.Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> entries =
                new java.util.HashSet<>();
        for (int i = 0; i < 100000; i++)
            entries.add(
                    new org.springframework.data.redis.core.DefaultTypedTuple<>(
                            "occupied-" + i, (double) (System.currentTimeMillis() + 60000)));
        redis.opsForZSet().add(index, entries);
        assertThrows(
                IllegalStateException.class, () -> bounded.consume(c("new"), List.of(p("u", 2, 1)), 1, 1));
        assertEquals(100000L, redis.opsForZSet().zCard(index));
        redis.delete(index);
    }

    @Test
    void realNetworkTimeoutHasUnknownOutcome() throws Exception {
        var client =
                org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                        .commandTimeout(java.time.Duration.ofMillis(40))
                        .build();
        var slow =
                new LettuceConnectionFactory(
                        new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                                factory.getHostName(), factory.getPort()),
                        client);
        slow.afterPropertiesSet();
        try {
            var template = new StringRedisTemplate(slow);
            template.opsForValue().get("warmup");
            try (var connection = factory.getConnection()) {
                connection.execute("CLIENT", "PAUSE".getBytes(), "200".getBytes(), "ALL".getBytes());
            }
            var subject = new RedisTokenStore(template, metrics, "timeout-" + UUID.randomUUID());
            assertThrows(
                    RuntimeException.class, () -> subject.consume(c("u"), List.of(p("u", 10, 1)), 1, 1));
            Thread.sleep(250);
            // The timeout is not proof of rejection; never retry the consume in application code.
            assertTrue(subject.inspect(c("u"), List.of(p("u", 10, 1)), 1).remainingTokens() <= 10);
        } finally {
            slow.destroy();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void restartStateDependsOnPersistence(boolean persist) throws Exception {
        try (var isolated =
                     new GenericContainer<>(DockerImageName.parse("redis:8.2.11-alpine"))
                             .withCommand("redis-server", "--save", persist ? "3600 1" : "", "--appendonly", "no")
                             .withExposedPorts(6379)) {
            isolated.start();
            var connection =
                    new LettuceConnectionFactory(isolated.getHost(), isolated.getMappedPort(6379));
            connection.afterPropertiesSet();
            try {
                var template = new StringRedisTemplate(connection);
                var subject = new RedisTokenStore(template, metrics, "restart");
                var ps = List.of(p("u", 1, .001));
                assertTrue(subject.consume(c("u"), ps, 1, 1).allowed());
                assertFalse(subject.consume(c("u"), ps, 1, 1).allowed());
                isolated.getDockerClient().restartContainerCmd(isolated.getContainerId()).exec();
                connection.destroy();
                var info = isolated.getDockerClient().inspectContainerCmd(isolated.getContainerId()).exec();
                int newPort =
                        Integer.parseInt(
                                info.getNetworkSettings()
                                        .getPorts()
                                        .getBindings()
                                        .get(com.github.dockerjava.api.model.ExposedPort.tcp(6379))[0]
                                        .getHostPortSpec());
                var shortTimeout =
                        org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                                .commandTimeout(java.time.Duration.ofMillis(250))
                                .build();
                connection =
                        new LettuceConnectionFactory(
                                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                                        isolated.getHost(), newPort),
                                shortTimeout);
                connection.afterPropertiesSet();
                template = new StringRedisTemplate(connection);
                subject = new RedisTokenStore(template, metrics, "restart");
                boolean restored = false;
                for (int i = 0; i < 40; i++) {
                    try {
                        template.opsForValue().get("ready");
                        restored = true;
                        break;
                    } catch (RuntimeException e) {
                        Thread.sleep(50);
                    }
                }
                assertTrue(restored);
                assertEquals(!persist, subject.consume(c("u"), ps, 1, 1).allowed());
            } finally {
                connection.destroy();
            }
        }
    }

    @Test
    void rejectedEvaluationAppliesRefillRateIncreaseWithoutSpendingOtherQuota() throws Exception {
        var slow = List.of(p("transition", 1, .001));
        assertTrue(store.consume(c("u"), slow, 1, 1).allowed());
        var fast = List.of(p("transition", 1, 1000));
        assertFalse(store.consume(c("u"), fast, 2, 1).allowed());
        Thread.sleep(10);
        assertTrue(store.consume(c("u"), fast, 2, 1).allowed());
    }

    @Test
    void differentEffectivePoliciesNeverRefillEachOthersBucket() {
        var restricted =
                new Policy(
                        "restricted",
                        "user",
                        Policy.Dimension.USER,
                        "*",
                        "*",
                        "/secure",
                        1,
                        .001,
                        Policy.FailureMode.CLOSED,
                        false,
                        true,
                        1);
        var permissive =
                new Policy(
                        "permissive",
                        "user",
                        Policy.Dimension.USER,
                        "*",
                        "*",
                        "/public",
                        100,
                        1000,
                        Policy.FailureMode.CLOSED,
                        false,
                        true,
                        1);
        assertTrue(store.consume(c("u"), List.of(restricted), 1, 1).allowed());
        assertTrue(store.consume(c("u"), List.of(permissive), 1, 1).allowed());
        assertFalse(store.consume(c("u"), List.of(restricted), 1, 1).allowed());
    }

    @Test
    void differentEffectivePoliciesCannotReuseLocalLeases() {
        var manager = new LocalQuotaManager(store, metrics);
        var permissive =
                new Policy(
                        "permissive",
                        "user",
                        Policy.Dimension.USER,
                        "*",
                        "*",
                        "/public",
                        16,
                        .001,
                        Policy.FailureMode.CLOSED,
                        true,
                        true,
                        1);
        var restricted =
                new Policy(
                        "restricted",
                        "user",
                        Policy.Dimension.USER,
                        "*",
                        "*",
                        "/secure",
                        1,
                        .001,
                        Policy.FailureMode.CLOSED,
                        true,
                        true,
                        1);
        assertTrue(manager.consume(c("u"), permissive, 1).allowed());
        assertTrue(manager.consume(c("u"), restricted, 1).allowed());
        assertFalse(manager.consume(c("u"), restricted, 1).allowed());
    }
}
