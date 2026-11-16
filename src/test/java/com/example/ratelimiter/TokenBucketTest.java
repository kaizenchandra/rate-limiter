package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ratelimiter.domain.TokenBucket;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class TokenBucketTest {
    @Test
    void initialExhaustionFractionalRefillAndRetry() {
        var now = new AtomicLong();
        var b = new TokenBucket(2, 2.5, now::get);
        assertEquals(1, b.take(1).remaining());
        assertTrue(b.take(1).allowed());
        assertEquals(400, b.take(1).retryAfterMs());
        now.set(200_000_000);
        assertFalse(b.take(1).allowed());
        now.set(400_000_000);
        assertTrue(b.take(1).allowed());
    }

    @Test
    void burstCapAndBackwardClockNeverMintTokens() {
        var now = new AtomicLong();
        var b = new TokenBucket(3, 1, now::get);
        assertTrue(b.take(3).allowed());
        now.set(-1_000_000_000);
        assertFalse(b.take(1).allowed());
        now.set(10_000_000_000L);
        assertEquals(2, b.take(1).remaining());
    }

    @Test
    void overflowBoundaryAndInvalidInput() {
        var b = new TokenBucket(1_000_000_000L, 1e9, System::nanoTime);
        assertFalse(b.take(Long.MAX_VALUE).allowed());
        assertThrows(IllegalArgumentException.class, () -> b.take(0));
        assertThrows(
                IllegalArgumentException.class, () -> new TokenBucket(Long.MAX_VALUE, 1, System::nanoTime));
    }

    @Test
    void randomizedReferenceEnvelopeAndBalanceInvariant() {
        var now = new AtomicLong();
        var b = new TokenBucket(17, 3.25, now::get);
        var random = new Random(91);
        long accepted = 0;
        for (int i = 0; i < 10000; i++) {
            now.addAndGet(random.nextInt(10000000));
            long cost = random.nextInt(5) + 1;
            var r = b.take(cost);
            if (r.allowed()) accepted += cost;
            assertTrue(r.remaining() >= 0 && r.remaining() <= 17);
            assertTrue(accepted <= 17 + 3.25 * now.get() / 1e9 + 1e-8);
        }
    }
}
