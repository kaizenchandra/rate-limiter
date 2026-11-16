package com.example.ratelimiter.domain;

import java.util.function.LongSupplier;

/**
 * Reference/local-only algorithm, not distributed enforcement. Clock supplies monotonic nanos.
 */
public final class TokenBucket {
    private final long capacity;
    private final double rate;
    private final LongSupplier clock;
    private double tokens;
    private long last;

    public TokenBucket(long capacity, double refillPerSecond, LongSupplier clock) {
        if (capacity < 1
                || capacity > 1_000_000_000L
                || !Double.isFinite(refillPerSecond)
                || refillPerSecond < .001
                || refillPerSecond > 1e9) throw new IllegalArgumentException("Invalid bucket bounds");
        this.capacity = capacity;
        this.rate = refillPerSecond;
        this.clock = clock;
        this.tokens = capacity;
        this.last = clock.getAsLong();
    }

    public synchronized Result take(long cost) {
        if (cost < 1) throw new IllegalArgumentException("Positive cost required");
        long now = clock.getAsLong();
        long elapsed = now - last;
        if (elapsed >= 0) {
            tokens = Math.min(capacity, tokens + Math.min(capacity, elapsed / 1e9 * rate));
            last = now;
        }
        if (cost > capacity) return new Result(false, (long) tokens, 0);
        if (tokens < cost)
            return new Result(false, (long) tokens, (long) Math.ceil((cost - tokens) / rate * 1000));
        tokens -= cost;
        return new Result(true, (long) tokens, 0);
    }

    public record Result(boolean allowed, long remaining, long retryAfterMs) {
    }
}
