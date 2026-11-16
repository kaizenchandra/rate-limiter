package com.example.ratelimiter.domain;

import jakarta.validation.constraints.*;

/**
 * Overrides replace the same ruleName; different rule names are conjunctive.
 */
public record Policy(
        @Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") @NotNull String id,
        @Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") @NotNull String ruleName,
        @NotNull Dimension dimension,
        @NotBlank @Size(max = 128) String tenant,
        @NotBlank @Pattern(regexp = "\\*|FREE|STANDARD|PREMIUM|ENTERPRISE") String tier,
        @NotBlank @Size(max = 128) String endpoint,
        @Min(1) @Max(1000000000) long capacity,
        @DecimalMin("0.001") @DecimalMax("1000000000") double refillPerSecond,
        @NotNull FailureMode failureMode,
        boolean lease,
        boolean enabled,
        @Min(0) long version) {
    public Policy withVersion(long v) {
        return new Policy(
                id,
                ruleName,
                dimension,
                tenant,
                tier,
                endpoint,
                capacity,
                refillPerSecond,
                failureMode,
                lease,
                enabled,
                v);
    }

    public void validateSemantics() {
        if (!Double.isFinite(refillPerSecond) || capacity / refillPerSecond > 86400)
            throw new IllegalArgumentException("Full refill time must be at most one day");
        if (!endpoint.equals("*") && !endpoint.matches("/[a-zA-Z0-9/_-]{1,127}"))
            throw new IllegalArgumentException("Endpoint must be a canonical route");
        if (dimension == Dimension.GLOBAL && (!tenant.equals("*") || !tier.equals("*")))
            throw new IllegalArgumentException("GLOBAL cannot have tenant/tier selectors");
    }

    public enum Dimension {
        GLOBAL,
        TENANT,
        USER,
        IP,
        API_KEY,
        ENDPOINT
    }

    public enum FailureMode {
        OPEN,
        CLOSED
    }
}
