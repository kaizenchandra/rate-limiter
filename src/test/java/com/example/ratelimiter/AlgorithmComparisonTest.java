package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ratelimiter.application.LocalQuotaManager;
import com.example.ratelimiter.domain.*;
import com.example.ratelimiter.infrastructure.redis.RedisTokenStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Small single-thread adapter comparison. Not JMH and not an HTTP/scale benchmark.
 */
@Tag("benchmark")
class AlgorithmComparisonTest {
    @Test
    void compareLocalDirectAndPrepaid() throws Exception {
        int n = Integer.getInteger("benchmark.requests", 10000);
        var f =
                new LettuceConnectionFactory("127.0.0.1", Integer.getInteger("benchmark.redis.port", 6379));
        f.afterPropertiesSet();
        try {
            var redis = new StringRedisTemplate(f);
            redis.opsForValue().get("warmup");
            var metrics = new SimpleMeterRegistry();
            var store = new RedisTokenStore(redis, metrics, "bench-" + UUID.randomUUID());
            var policy =
                    new Policy(
                            "bench",
                            "bench",
                            Policy.Dimension.USER,
                            "*",
                            "*",
                            "*",
                            1000000000,
                            1000000000,
                            Policy.FailureMode.CLOSED,
                            true,
                            true,
                            1);
            var context = new Context("bench", "one-hot-user", "key", "127.0.0.1", "FREE", "/demo", 1);
            var manager = new LocalQuotaManager(store, metrics);
            var local = new TokenBucket(1000000000, 1e9, System::nanoTime);
            List<String> rows = new ArrayList<>();
            rows.add("mode,requests,allowed,elapsed_ms,ops_per_second,p50_us,p95_us,p99_us");
            rows.add(measure("local-only-java", n, () -> local.take(1).allowed()));
            rows.add(
                    measure(
                            "direct-redis", n, () -> store.consume(context, List.of(policy), 1, 1).allowed()));
            rows.add(measure("prepaid-lease", n, () -> manager.consume(context, policy, 1).allowed()));
            Path path = Path.of("benchmarks/results/adapter-comparison.csv");
            Files.createDirectories(path.getParent());
            Files.write(path, rows);
            rows.forEach(System.out::println);
        } finally {
            f.destroy();
        }
    }

    private String measure(String mode, int n, BooleanSupplier operation) {
        for (int i = 0; i < 1000; i++) operation.getAsBoolean();
        long[] ns = new long[n];
        long allowed = 0, start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long before = System.nanoTime();
            if (operation.getAsBoolean()) allowed++;
            ns[i] = System.nanoTime() - before;
        }
        long elapsed = System.nanoTime() - start;
        Arrays.sort(ns);
        assertEquals(n, allowed);
        return String.format(
                java.util.Locale.ROOT,
                "%s,%d,%d,%.3f,%.1f,%.3f,%.3f,%.3f",
                mode,
                n,
                allowed,
                elapsed / 1e6,
                n / (elapsed / 1e9),
                ns[n / 2] / 1e3,
                ns[(int) (n * .95)] / 1e3,
                ns[(int) (n * .99)] / 1e3);
    }
}
