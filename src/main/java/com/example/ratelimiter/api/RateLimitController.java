package com.example.ratelimiter.api;

import com.example.ratelimiter.application.*;
import com.example.ratelimiter.domain.*;
import com.example.ratelimiter.security.IdentityResolver;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.*;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class RateLimitController {
    private final RateLimitEngine engine;
    private final IdentityResolver identity;

    public RateLimitController(RateLimitEngine engine, IdentityResolver identity) {
        this.engine = engine;
        this.identity = identity;
    }

    @PostMapping("/api/v1/rate-limits/evaluate")
    public Decision evaluate(
            @Valid @RequestBody Evaluation input,
            Authentication auth,
            HttpServletRequest r,
            HttpServletResponse response) {
        var d = engine.evaluate(identity.resolve(auth, r, input.endpoint(), input.cost()));
        Headers.apply(response, d);
        response.setStatus(d.allowed() ? 200 : 429);
        return d;
    }

    @GetMapping("/api/v1/rate-limits/effective")
    public List<Policy> effective(
            @RequestParam String endpoint, Authentication auth, HttpServletRequest r) {
        if (!endpoint.matches("/[a-zA-Z0-9/_-]{1,127}"))
            throw new IllegalArgumentException("Invalid endpoint");
        return engine.effective(identity.resolve(auth, r, endpoint, 1));
    }

    @GetMapping("/api/v1/rate-limits/status")
    public Decision status(@RequestParam String endpoint, Authentication auth, HttpServletRequest r) {
        if (!endpoint.matches("/[a-zA-Z0-9/_-]{1,127}"))
            throw new IllegalArgumentException("Invalid endpoint");
        return engine.inspect(identity.resolve(auth, r, endpoint, 1));
    }

    @GetMapping("/protected/demo")
    public Map<String, String> demo() {
        return Map.of("message", "Request admitted");
    }

    public record Evaluation(
            @NotBlank @Pattern(regexp = "/[a-zA-Z0-9/_-]{1,127}") String endpoint,
            @Min(1) @Max(1000000) long cost) {
    }
}
