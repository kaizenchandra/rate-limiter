package com.example.ratelimiter.api;

import com.example.ratelimiter.domain.Decision;
import jakarta.servlet.http.HttpServletResponse;

public final class Headers {
    private Headers() {
    }

    public static void apply(HttpServletResponse r, Decision d) {
        r.setHeader("RateLimit-Remaining", Long.toString(d.remainingTokens()));
        r.setHeader("Cache-Control", "no-store");
        if (!d.allowed() && d.retryAfterMs() > 0)
            r.setHeader("Retry-After", Long.toString(Math.max(1, (d.retryAfterMs() + 999) / 1000)));
        r.setHeader("X-RateLimit-Mode", d.mode());
    }
}
