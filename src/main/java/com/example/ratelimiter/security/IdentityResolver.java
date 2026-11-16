package com.example.ratelimiter.security;

import com.example.ratelimiter.domain.Context;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class IdentityResolver {
    public Context resolve(
            Authentication authentication, HttpServletRequest request, String endpoint, long cost) {
        if (!(authentication instanceof JwtAuthenticationToken a))
            throw new IllegalArgumentException("JWT identity required");
        var jwt = a.getToken();
        String tenant = jwt.getClaimAsString("tenant_id");
        String subject = jwt.getSubject();
        String key = jwt.getClaimAsString("api_key_id");
        String tier = jwt.getClaimAsString("tier");
        if (tenant == null
                || subject == null
                || !tenant.matches("[a-zA-Z0-9_-]{1,128}")
                || !subject.matches("[a-zA-Z0-9@._-]{1,128}"))
            throw new IllegalArgumentException("Trusted tenant and subject claims required");
        if (tier == null) tier = "FREE";
        if (!tier.matches("FREE|STANDARD|PREMIUM|ENTERPRISE"))
            throw new IllegalArgumentException("Invalid tier");
        if (key != null && !key.matches("[a-zA-Z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid trusted API key ID");
        return new Context(
                tenant, subject, key == null ? "none" : key, request.getRemoteAddr(), tier, endpoint, cost);
    }
}
