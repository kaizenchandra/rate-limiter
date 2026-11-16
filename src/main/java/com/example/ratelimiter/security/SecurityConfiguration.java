package com.example.ratelimiter.security;

import com.example.ratelimiter.api.EnforcementFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

@Configuration
public class SecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${limiter.jwk-set-uri}") String uri,
            @Value("${limiter.issuer}") String issuer,
            @Value("${limiter.audience}") String audience) {
        var requests = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(500);
        requests.setReadTimeout(500);
        var decoder =
                NimbusJwtDecoder.withJwkSetUri(uri)
                        .restOperations(new RestTemplate(requests))
                        .build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        jwt ->
                                jwt.getAudience().contains(audience)
                                        ? OAuth2TokenValidatorResult.success()
                                        : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error("invalid_token", "Invalid audience", null))));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, EnforcementFilter filter) throws Exception {
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers("/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers("/actuator/prometheus")
                                        .hasAuthority("SCOPE_metrics")
                                        .requestMatchers("/api/v1/policies/**")
                                        .hasAuthority("SCOPE_limiter.admin")
                                        .requestMatchers("/api/v1/rate-limits/**")
                                        .hasAuthority("SCOPE_limiter.evaluate")
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> {
                }))
                .addFilterAfter(filter, BearerTokenAuthenticationFilter.class)
                .build();
    }
}
