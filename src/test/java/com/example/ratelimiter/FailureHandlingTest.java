package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.ratelimiter.application.*;
import com.example.ratelimiter.domain.*;
import com.example.ratelimiter.infrastructure.persistence.*;
import com.example.ratelimiter.infrastructure.redis.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.*;

import org.junit.jupiter.api.*;

class FailureHandlingTest {
    @Test
    void ambiguousRedisFailureIsNotRetriedAndHonorsPolicy() {
        var cache = mock(PolicyCache.class);
        var store = mock(RedisTokenStore.class);
        var metrics = new SimpleMeterRegistry();
        var c = PolicyResolverTest.context();
        var closed = PolicyResolverTest.p("p", "rule", "*", "*", "*", true);
        when(cache.current())
                .thenReturn(new PolicyRepository.Snapshot(1, List.of(closed), System.nanoTime()));
        when(store.consume(any(), anyList(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("timeout after write"));
        var engine =
                new RateLimitEngine(
                        cache, new PolicyResolver(), store, mock(LocalQuotaManager.class), metrics, false);
        assertThrows(PolicyCache.Unavailable.class, () -> engine.evaluate(c));
        verify(store, times(1)).consume(any(), anyList(), anyLong(), anyLong());
        var open =
                new Policy(
                        "p",
                        "rule",
                        Policy.Dimension.USER,
                        "*",
                        "*",
                        "*",
                        10,
                        1,
                        Policy.FailureMode.OPEN,
                        false,
                        true,
                        1);
        when(cache.current())
                .thenReturn(new PolicyRepository.Snapshot(1, List.of(open), System.nanoTime()));
        assertEquals("fail-open", engine.evaluate(c).mode());
    }

    @Test
    void missingOrStaleConfigurationFailsClosed() {
        var repo = mock(PolicyRepository.class);
        var cache = new PolicyCache(repo, new SimpleMeterRegistry());
        assertThrows(PolicyCache.Unavailable.class, cache::current);
        when(repo.load())
                .thenReturn(
                        new PolicyRepository.Snapshot(1, List.of(), System.nanoTime() - 6_000_000_000L));
        cache.refresh();
        assertThrows(PolicyCache.Unavailable.class, cache::current);
    }
}
