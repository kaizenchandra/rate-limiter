package com.example.ratelimiter.security;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtValidationTest {
    @Test
    void validatesRealSignatureIssuerAudienceAndExpiration() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test").generate();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/jwks",
                ex -> {
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, jwks.length);
                    ex.getResponseBody().write(jwks);
                    ex.close();
                });
        server.start();
        try {
            var decoder =
                    new SecurityConfiguration()
                            .jwtDecoder(
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks",
                                    "https://issuer.example",
                                    "rate-limiter");
            assertEquals(
                    "alice",
                    decoder.decode(token(key, "https://issuer.example", "rate-limiter", 3600)).getSubject());
            assertThrows(
                    JwtException.class,
                    () -> decoder.decode(token(key, "https://evil.example", "rate-limiter", 3600)));
            assertThrows(
                    JwtException.class,
                    () -> decoder.decode(token(key, "https://issuer.example", "other-service", 3600)));
            assertThrows(
                    JwtException.class,
                    () -> decoder.decode(token(key, "https://issuer.example", "rate-limiter", -300)));
            var impostor = new RSAKeyGenerator(2048).keyID("test").generate();
            assertThrows(
                    JwtException.class,
                    () -> decoder.decode(token(impostor, "https://issuer.example", "rate-limiter", 3600)));
        } finally {
            server.stop(0);
        }
    }

    private String token(RSAKey key, String issuer, String audience, long expirationOffset)
            throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(audience)
                        .subject("alice")
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + expirationOffset * 1000))
                        .build();
        var jwt =
                new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void jwksFetchTimeoutIsBounded() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("slow").generate();
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext(
                "/slow",
                exchange -> {
                    try {
                        release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            var decoder =
                    new SecurityConfiguration()
                            .jwtDecoder(
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/slow",
                                    "https://issuer.example",
                                    "rate-limiter");
            long start = System.nanoTime();
            assertThrows(
                    JwtException.class,
                    () -> decoder.decode(token(key, "https://issuer.example", "rate-limiter", 3600)));
            assertTrue(
                    System.nanoTime() - start < 3_000_000_000L,
                    "JWKS retrieval must not hold a request indefinitely");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
