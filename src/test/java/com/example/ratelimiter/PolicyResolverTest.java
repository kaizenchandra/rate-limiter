package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ratelimiter.application.*;
import com.example.ratelimiter.domain.*;
import jakarta.validation.Validation;

import java.util.*;

import org.junit.jupiter.api.Test;

class PolicyResolverTest {
    static Policy p(
            String id, String rule, String tenant, String tier, String endpoint, boolean enabled) {
        return new Policy(
                id,
                rule,
                Policy.Dimension.USER,
                tenant,
                tier,
                endpoint,
                10,
                1,
                Policy.FailureMode.CLOSED,
                false,
                enabled,
                1);
    }

    static Context context() {
        return new Context("acme", "user", "key", "127.0.0.1", "PREMIUM", "/protected/demo", 1);
    }

    @Test
    void inheritanceAndDeterministicPrecedence() {
        var a = p("default", "user", "*", "*", "*", true);
        var b = p("tier", "user", "*", "PREMIUM", "*", true);
        var c = p("tenant", "user", "acme", "*", "*", true);
        var r = new PolicyResolver();
        assertEquals(List.of(c), r.resolve(context(), List.of(c, a, b)));
        assertEquals(List.of(b), r.resolve(context(), List.of(a, b)));
    }

    @Test
    void disabledOverrideSuppressesInheritedRule() {
        assertTrue(
                new PolicyResolver()
                        .resolve(
                                context(),
                                List.of(
                                        p("a", "user", "*", "*", "*", true), p("b", "user", "acme", "*", "*", false)))
                        .isEmpty());
    }

    @Test
    void independentRulesAreConjunctiveAndOrderStable() {
        var a = p("a", "a", "*", "*", "*", true);
        var b = p("b", "b", "*", "*", "*", true);
        assertEquals(List.of(a, b), new PolicyResolver().resolve(context(), List.of(b, a)));
    }

    @Test
    void validatesBoundaryAndNonfiniteNumbers() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(p("good", "user", "*", "*", "*", true)).isEmpty());
            var invalid =
                    new Policy(
                            "bad",
                            "user",
                            Policy.Dimension.USER,
                            "*",
                            "*",
                            "*",
                            Long.MAX_VALUE,
                            1,
                            Policy.FailureMode.CLOSED,
                            false,
                            true,
                            0);
            assertFalse(factory.getValidator().validate(invalid).isEmpty());
            assertThrows(IllegalArgumentException.class, invalid::validateSemantics);
        }
        var invalid =
                new Policy(
                        "bad",
                        "user",
                        Policy.Dimension.USER,
                        "*",
                        "*",
                        "*",
                        10,
                        Double.NaN,
                        Policy.FailureMode.CLOSED,
                        false,
                        true,
                        0);
        assertThrows(IllegalArgumentException.class, invalid::validateSemantics);
    }

    @Test
    void tieBreaksByIdIndependentOfInputOrder() {
        var a = p("a", "same", "*", "*", "*", true);
        var b = p("b", "same", "*", "*", "*", true);
        assertEquals(List.of(a), new PolicyResolver().resolve(context(), List.of(b, a)));
    }
}
