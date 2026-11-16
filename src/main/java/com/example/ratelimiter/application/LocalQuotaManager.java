package com.example.ratelimiter.application;

import com.example.ratelimiter.domain.*;
import com.example.ratelimiter.infrastructure.redis.RedisTokenStore;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.*;

import org.springframework.stereotype.Component;

/**
 * Bounded cache. Nonrenewable prepaid leases; no refunds. Single monitor avoids renewal races.
 */
@Component
public class LocalQuotaManager {
    private final Map<String, Lease> leases = new LinkedHashMap<>();
    private final TokenStore store;
    private final MeterRegistry metrics;

    public LocalQuotaManager(RedisTokenStore store, MeterRegistry metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    public synchronized Decision consume(Context c, Policy p, long revision) {
        String key =
                RedisTokenStore.digest(p.id() + ":" + p.dimension() + ":" + c.identity(p.dimension()));
        long now = System.nanoTime();
        Lease l = leases.get(key);
        if (l != null && l.revision == revision && now < l.deadline && l.remaining > 0) {
            leases.put(key, new Lease(revision, l.deadline, l.remaining - 1));
            return new Decision(true, l.remaining - 1, 0, List.of(p.id()), "lease");
        }
        metrics.counter("limiter.lease.exhaustion").increment();
        // Timestamp before allocation: a slow response cannot extend the lease.
        long deadline = now + 100_000_000L;
        long size = Math.min(16, p.capacity());
        Decision grant = store.consume(c, List.of(p), revision, size);
        if (!grant.allowed())
            return store.consume(c, List.of(p), revision, 1); // known rejection, safe smaller request
        metrics.counter("limiter.lease.allocations").increment();
        if (System.nanoTime() >= deadline)
            return new Decision(false, 0, 1, List.of(p.id()), "expired-lease");
        if (leases.size() >= 10000) leases.remove(leases.keySet().iterator().next());
        leases.put(key, new Lease(revision, deadline, size - 1));
        return new Decision(true, size - 1, 0, List.of(p.id()), "lease");
    }

    private record Lease(long revision, long deadline, long remaining) {
    }
}
