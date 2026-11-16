package com.example.ratelimiter.domain;

import java.util.List;

public record Decision(
        boolean allowed,
        long remainingTokens,
        long retryAfterMs,
        List<String> policyIds,
        String mode) {
}
