package com.ifp.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real RS256 tokens plus a JWK endpoint whose published key set can be changed during a test (key rotation).
 * The gateway's production decoder fetches keys from here exactly as it would from auth-service.
 */
public final class TestTokens {

    public static final String ISSUER = "http://auth.test.ifp.local";
    public static final String AUDIENCE = "ifp-api";

    public static final RSAKey PRIMARY_KEY = generate("primary-key");
    /** Never published unless a test rotates to it. */
    public static final RSAKey NEXT_KEY = generate("next-key");
    /** Reuses the primary key ID with different key material: only the signature check can reject it. */
    public static final RSAKey ATTACKER_KEY = generate("primary-key");

    private static final AtomicReference<JWKSet> PUBLISHED = new AtomicReference<>(new JWKSet(PRIMARY_KEY.toPublicJWK()));
    private static final AtomicInteger JWKS_FETCHES = new AtomicInteger();
    private static final HttpServer SERVER = start();

    private TestTokens() {
    }

    public static String jwkSetUri() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/jwks";
    }

    public static void publish(RSAKey... keys) {
        PUBLISHED.set(new JWKSet(List.of(keys).stream().map(k -> (JWK) k.toPublicJWK()).toList()));
    }

    public static int jwksFetches() {
        return JWKS_FETCHES.get();
    }

    public static Builder token() {
        return new Builder();
    }

    public static String seller(UUID id) {
        return token().subject(id.toString()).roles("SELLER").sign();
    }

    public static String admin(UUID id) {
        return token().subject(id.toString()).roles("ADMIN").sign();
    }

    public static final class Builder {
        private String subject = UUID.randomUUID().toString();
        private List<String> roles = List.of("SELLER");
        private String issuer = ISSUER;
        private String audience = AUDIENCE;
        private Instant expiresAt = Instant.now().plusSeconds(300);
        private boolean withExpiry = true;
        private RSAKey key = PRIMARY_KEY;

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder roles(String... roles) {
            this.roles = List.of(roles);
            return this;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(String audience) {
            this.audience = audience;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder withoutExpiry() {
            this.withExpiry = false;
            return this;
        }

        public Builder signedWith(RSAKey key) {
            this.key = key;
            return this;
        }

        public String sign() {
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims());
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }

        public String signHs256() {
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(PRIMARY_KEY.getKeyID()).build(), claims());
                jwt.sign(new MACSigner("an-hmac-secret-that-is-at-least-256-bits-long!!".getBytes(StandardCharsets.UTF_8)));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }

        private JWTClaimsSet claims() {
            Instant now = Instant.now();
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(subject).issuer(issuer).audience(audience)
                    .issueTime(Date.from(now.minusSeconds(5)))
                    .claim("roles", roles)
                    .jwtID(UUID.randomUUID().toString());
            if (withExpiry) {
                claims.expirationTime(Date.from(expiresAt));
            }
            return claims.build();
        }
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                JWKS_FETCHES.incrementAndGet();
                byte[] body = PUBLISHED.get().toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start test JWKS server", e);
        }
    }
}
