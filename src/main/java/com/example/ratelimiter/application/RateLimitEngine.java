package com.example.ratelimiter.application;

import com.example.ratelimiter.domain.*;
import com.example.ratelimiter.infrastructure.redis.RedisTokenStore;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RateLimitEngine {
    private final PolicyCache cache;
    private final PolicyResolver resolver;
    private final RedisTokenStore store;
    private final LocalQuotaManager local;
    private final MeterRegistry metrics;
    private final boolean leasing;
    private final Semaphore bulkhead = new Semaphore(128);

    public RateLimitEngine(
            PolicyCache cache,
            PolicyResolver resolver,
            RedisTokenStore store,
            LocalQuotaManager local,
            MeterRegistry metrics,
            @Value("${limiter.leasing:false}") boolean leasing) {
        this.cache = cache;
        this.resolver = resolver;
        this.store = store;
        this.local = local;
        this.metrics = metrics;
        this.leasing = leasing;
    }

    public Decision evaluate(Context c) {
        var sample = io.micrometer.core.instrument.Timer.start(metrics);
        try {
            var s = cache.current();
            var ps = resolver.resolve(c, s.policies());
            if (ps.isEmpty())
                throw new PolicyCache.Unavailable("No applicable policy; refusing unconfigured traffic");
            Decision d;
            if (!bulkhead.tryAcquire())
                throw new PolicyCache.Unavailable("Decision concurrency bound reached");
            try {
                try {
                    d =
                            leasing && ps.size() == 1 && ps.getFirst().lease() && c.cost() == 1
                                    ? local.consume(c, ps.getFirst(), s.revision())
                                    : store.consume(c, ps, s.revision(), c.cost());
                } catch (RuntimeException e) {
                    metrics.counter("limiter.redis.failures").increment();
                    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                        if (cause instanceof io.lettuce.core.RedisCommandTimeoutException
                                || cause instanceof java.util.concurrent.TimeoutException) {
                            metrics.counter("limiter.timeouts").increment();
                            break;
                        }
                    }
                    // Unknown outcomes are never retried. No fallback allowance is minted locally.
                    boolean open = ps.stream().allMatch(p -> p.failureMode() == Policy.FailureMode.OPEN);
                    metrics.counter("limiter.fallback", "mode", open ? "open" : "closed").increment();
                    if (!open)
                        throw new PolicyCache.Unavailable("Coordination unavailable; outcome may be unknown");
                    d = new Decision(true, 0, 0, ps.stream().map(Policy::id).toList(), "fail-open");
                }
            } finally {
                bulkhead.release();
            }
            metrics
                    .counter(
                            "limiter.decisions", "result", d.allowed() ? "allowed" : "rejected", "mode", d.mode())
                    .increment();
            return d;
        } finally {
            sample.stop(metrics.timer("limiter.decision.latency"));
        }
    }

    public Decision inspect(Context c) {
        var s = cache.current();
        var ps = resolver.resolve(c, s.policies());
        if (ps.isEmpty()) throw new PolicyCache.Unavailable("No applicable policy");
        if (!bulkhead.tryAcquire())
            throw new PolicyCache.Unavailable("Decision concurrency bound reached");
        try {
            return store.inspect(c, ps, s.revision());
        } catch (RuntimeException e) {
            throw new PolicyCache.Unavailable("Inspection unavailable");
        } finally {
            bulkhead.release();
        }
    }

    public java.util.List<Policy> effective(Context c) {
        return resolver.resolve(c, cache.current().policies());
    }
}
