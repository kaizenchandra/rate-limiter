package com.example.ratelimiter.infrastructure.redis;

import com.example.ratelimiter.domain.*;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisTokenStore implements TokenStore {
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final String prefix;
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();

    public RedisTokenStore(
            StringRedisTemplate redis,
            MeterRegistry metrics,
            @Value("${limiter.domain:region-a}") String domain) {
        if (!domain.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid coordination domain");
        this.redis = redis;
        this.metrics = metrics;
        prefix = "rl:{" + domain + "}:";
        script.setLocation(new ClassPathResource("redis/consume.lua"));
        script.setResultType(List.class);
    }

    public static String digest(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Decision consume(Context c, List<Policy> ps, long revision, long cost) {
        return execute(c, ps, revision, cost, false);
    }

    public Decision inspect(Context c, List<Policy> ps, long revision) {
        return execute(c, ps, revision, c.cost(), true);
    }

    private Decision execute(Context c, List<Policy> ps, long revision, long cost, boolean inspect) {
        if (ps.isEmpty()) return new Decision(true, 0, 0, List.of(), "unlimited");
        if (ps.size() > 16) throw new IllegalArgumentException("At most sixteen applicable policies");
        List<String> keys = new ArrayList<>();
        keys.add(prefix + "revision");
        keys.add(prefix + "identities");
        List<String> args =
                new ArrayList<>(List.of(Long.toString(revision), Long.toString(cost), "100000"));
        for (Policy p : ps) {
            if (cost > p.capacity())
                return new Decision(
                        false, 0, 0, ps.stream().map(Policy::id).toList(), "cost-exceeds-capacity");
            keys.add(prefix + digest(p.id() + ":" + p.dimension() + ":" + c.identity(p.dimension())));
            args.add(Long.toString(p.capacity()));
            args.add(Double.toString(p.refillPerSecond()));
        }
        args.add(inspect ? "inspect" : "consume");
        var sample = io.micrometer.core.instrument.Timer.start(metrics);
        try {
            List<?> out = redis.execute(script, keys, args.toArray());
            if (out == null) throw new IllegalStateException("Missing Redis result");
            long status = ((Number) out.getFirst()).longValue();
            if (status < 0)
                throw new IllegalStateException(
                        status == -2 ? "Bucket cardinality bound reached" : "Stale policy revision");
            return new Decision(
                    ((Number) out.getFirst()).longValue() == 1,
                    ((Number) out.get(1)).longValue(),
                    ((Number) out.get(2)).longValue(),
                    ps.stream().map(Policy::id).toList(),
                    inspect ? "inspection" : "redis");
        } finally {
            sample.stop(metrics.timer("limiter.redis.latency"));
        }
    }
}
