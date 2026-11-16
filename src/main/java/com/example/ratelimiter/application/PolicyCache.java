package com.example.ratelimiter.application;

import com.example.ratelimiter.infrastructure.persistence.PolicyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PolicyCache {
    private final PolicyRepository repository;
    private final MeterRegistry metrics;
    private volatile PolicyRepository.Snapshot snapshot;

    public PolicyCache(PolicyRepository repository, MeterRegistry metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${limiter.policy-poll-ms:1000}")
    public synchronized void refresh() {
        try {
            var s = repository.load();
            if (s.policies().size() > 10000)
                throw new IllegalStateException("Policy count exceeds bound");
            snapshot = s;
        } catch (RuntimeException e) {
            metrics.counter("limiter.policy.propagation.failures").increment();
        }
    }

    public PolicyRepository.Snapshot current() {
        var s = snapshot;
        if (s == null || System.nanoTime() - s.loadedNanos() > 5_000_000_000L) {
            metrics.counter("limiter.policy.cache.misses").increment();
            throw new Unavailable("Policy snapshot unavailable or older than five seconds");
        }
        metrics.counter("limiter.policy.cache.hits").increment();
        return s;
    }

    public static class Unavailable extends RuntimeException {
        public Unavailable(String m) {
            super(m);
        }
    }
}
