package com.example.ratelimiter.api;

import com.example.ratelimiter.application.*;
import com.example.ratelimiter.security.IdentityResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.IOException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class EnforcementFilter extends OncePerRequestFilter {
    private final RateLimitEngine engine;
    private final IdentityResolver identity;
    private final ObjectMapper json;

    public EnforcementFilter(RateLimitEngine engine, IdentityResolver identity, ObjectMapper json) {
        this.engine = engine;
        this.identity = identity;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        return !r.getRequestURI().startsWith("/protected/")
                || SecurityContextHolder.getContext().getAuthentication() == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest r, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            var d =
                    engine.evaluate(
                            identity.resolve(
                                    SecurityContextHolder.getContext().getAuthentication(), r, "/protected/demo", 1));
            Headers.apply(response, d);
            if (!d.allowed()) {
                response.setStatus(429);
                response.setContentType("application/json");
                json.writeValue(response.getOutputStream(), d);
                return;
            }
        } catch (PolicyCache.Unavailable e) {
            response.sendError(503, "Rate limiter unavailable");
            return;
        } catch (IllegalArgumentException e) {
            response.sendError(400, "Invalid identity");
            return;
        }
        chain.doFilter(r, response);
    }
}
