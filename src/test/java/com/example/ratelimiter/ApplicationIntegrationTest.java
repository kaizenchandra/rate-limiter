package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.ratelimiter.application.PolicyCache;
import com.example.ratelimiter.infrastructure.persistence.PolicyRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@org.springframework.test.annotation.DirtiesContext
@Tag("integration")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationIntegrationTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:8.2.11-alpine").withExposedPorts(6379);
    @Autowired
    MockMvc mvc;
    @Autowired
    PolicyRepository repository;
    @Autowired
    PolicyCache cache;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", pg::getJdbcUrl);
        p.add("spring.datasource.username", pg::getUsername);
        p.add("spring.datasource.password", pg::getPassword);
        p.add("spring.data.redis.host", redis::getHost);
        p.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        p.add("limiter.issuer", () -> "https://issuer.example");
        p.add("limiter.jwk-set-uri", () -> "https://issuer.example/jwks");
        p.add("limiter.audience", () -> "rate-limiter");
        p.add("management.otlp.tracing.export.enabled", () -> false);
    }

    @Test
    void securityCrudHistoryAndNative429() throws Exception {
        mvc.perform(get("/api/v1/policies")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/policies").with(jwt())).andExpect(status().isForbidden());
        String body =
                """
                        {"id":"user","ruleName":"user","dimension":"USER","tenant":"*","tier":"*","endpoint":"*","capacity":1,"refillPerSecond":0.001,"failureMode":"CLOSED","lease":false,"enabled":true,"version":0}
                        """;
        var admin = jwt().authorities(new SimpleGrantedAuthority("SCOPE_limiter.admin"));
        mvc.perform(post("/api/v1/policies").with(admin).contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/api/v1/policies").with(admin).contentType("application/json").content(body))
                .andExpect(status().isConflict());
        var user = jwt().jwt(j -> j.subject("alice").claim("tenant_id", "acme"));
        mvc.perform(get("/protected/demo").with(user))
                .andExpect(status().isOk())
                .andExpect(header().string("RateLimit-Remaining", "0"));
        mvc.perform(get("/protected/demo").with(user))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(get("/api/v1/policies/user/versions").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1));
        mvc.perform(
                        put("/api/v1/policies/user")
                                .with(admin)
                                .header("If-Match", 1)
                                .contentType("application/json")
                                .content(body.replace("\"capacity\":1,", "\"capacity\":2,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(delete("/api/v1/policies/user").with(admin).header("If-Match", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        assertEquals(3, repository.history("user").size());
        cache.refresh();
        assertEquals(3, cache.current().revision());
    }

    @Test
    void rejectsIdentitySpoofingAndLargePayload() throws Exception {
        var evaluator =
                jwt()
                        .jwt(j -> j.subject("bob").claim("tenant_id", "acme"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_limiter.evaluate"));
        mvc.perform(
                        post("/api/v1/rate-limits/evaluate")
                                .with(evaluator)
                                .contentType("application/json")
                                .content("{\"tenantId\":\"victim\",\"endpoint\":\"/demo\",\"cost\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/v1/rate-limits/evaluate")
                                .with(evaluator)
                                .contentType("application/json")
                                .content("x".repeat(9000)))
                .andExpect(status().isPayloadTooLarge());
    }
}
