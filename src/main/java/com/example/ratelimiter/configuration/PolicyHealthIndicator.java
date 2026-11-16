package com.example.ratelimiter.configuration;

import com.example.ratelimiter.application.PolicyCache;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

@Component("policy")
public class PolicyHealthIndicator implements HealthIndicator {
    private final PolicyCache cache;

    public PolicyHealthIndicator(PolicyCache cache) {
        this.cache = cache;
    }

    public Health health() {
        try {
            var s = cache.current();
            return s.policies().isEmpty() ? Health.down().build() : Health.up().build();
        } catch (RuntimeException e) {
            return Health.down().build();
        }
    }
}
