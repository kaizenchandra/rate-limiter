package com.example.ratelimiter.api;

import com.example.ratelimiter.application.PolicyCache;
import com.example.ratelimiter.domain.Policy;
import com.example.ratelimiter.infrastructure.persistence.PolicyRepository;
import com.example.ratelimiter.infrastructure.redis.RedisTokenStore;
import jakarta.validation.Valid;

import java.util.*;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {
    private final PolicyRepository repository;
    private final PolicyCache cache;

    public PolicyController(PolicyRepository repository, PolicyCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    @GetMapping
    public PolicyRepository.Snapshot list() {
        return repository.load();
    }

    @GetMapping("/{id}/versions")
    public List<Policy> versions(@PathVariable String id) {
        return repository.history(id);
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Policy create(@Valid @RequestBody Policy p, Authentication auth) {
        return save(p, 0, auth);
    }

    @PutMapping("/{id}")
    public Policy update(
            @PathVariable String id,
            @RequestHeader("If-Match") long version,
            @Valid @RequestBody Policy p,
            Authentication auth) {
        if (!id.equals(p.id())) throw new IllegalArgumentException("ID mismatch");
        return save(p, version, auth);
    }

    @DeleteMapping("/{id}")
    public Policy disable(
            @PathVariable String id, @RequestHeader("If-Match") long version, Authentication auth) {
        Policy p =
                repository.load().policies().stream()
                        .filter(v -> v.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown policy"));
        return save(
                new Policy(
                        p.id(),
                        p.ruleName(),
                        p.dimension(),
                        p.tenant(),
                        p.tier(),
                        p.endpoint(),
                        p.capacity(),
                        p.refillPerSecond(),
                        p.failureMode(),
                        p.lease(),
                        false,
                        p.version()),
                version,
                auth);
    }

    private Policy save(Policy p, long version, Authentication auth) {
        var result = repository.save(p, version, RedisTokenStore.digest(auth.getName()));
        cache.refresh();
        return result;
    }
}
