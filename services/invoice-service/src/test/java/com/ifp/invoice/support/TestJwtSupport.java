package com.ifp.invoice.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues real RS256-signed JWTs for tests and serves the matching public JWK set over HTTP, so the service's
 * production JwtDecoder (JWK fetch, signature, issuer, audience and algorithm checks) is exercised unchanged.
 */
public final class TestJwtSupport {

    public static final String ISSUER = "http://auth.test.ifp.local";
    public static final String AUDIENCE = "ifp-api";
    private static final String KEY_ID = "test-signing-key";

    private static final RSAKey TRUSTED_KEY = generateKey();
    // Same key ID as the trusted key, so only the signature check can reject it.
    private static final RSAKey ATTACKER_KEY = generateKey();
    private static final HttpServer JWKS_SERVER = startJwksServer();

    private TestJwtSupport() {
    }

    public static String jwkSetUri() {
        return "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/jwks";
    }

    public static TokenBuilder token() {
        return new TokenBuilder();
    }

    public static String seller(UUID userId) {
        return token().subject(userId.toString()).roles("SELLER").sign();
    }

    public static String admin(UUID userId) {
        return token().subject(userId.toString()).roles("ADMIN").sign();
    }

    public static String financier(UUID userId) {
        return token().subject(userId.toString()).roles("FINANCIER").sign();
    }

    public static final class TokenBuilder {
        private String subject = UUID.randomUUID().toString();
        private List<String> roles = List.of("SELLER");
        private String issuer = ISSUER;
        private String audience = AUDIENCE;
        private Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
        private RSAKey signingKey = TRUSTED_KEY;

        public TokenBuilder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public TokenBuilder roles(String... roles) {
            this.roles = List.of(roles);
            return this;
        }

        public TokenBuilder withoutRoles() {
            this.roles = null;
            return this;
        }

        public TokenBuilder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public TokenBuilder audience(String audience) {
            this.audience = audience;
            return this;
        }

        public TokenBuilder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public TokenBuilder signedByUntrustedKey() {
            this.signingKey = ATTACKER_KEY;
            return this;
        }

        public String sign() {
            try {
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims());
                jwt.sign(new RSASSASigner(signingKey));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Symmetric HS256 token; must be rejected because only RS256 is accepted. */
        public String signHs256(byte[] secret) {
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY_ID).build(), claims());
                jwt.sign(new MACSigner(secret));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }

        private JWTClaimsSet claims() {
            Instant now = Instant.now();
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .audience(audience)
                    .issueTime(Date.from(now.minusSeconds(5)))
                    .notBeforeTime(Date.from(now.minusSeconds(5)))
                    .expirationTime(Date.from(expiresAt))
                    .jwtID(UUID.randomUUID().toString());
            if (roles != null) {
                claims.claim("roles", roles);
            }
            return claims.build();
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(TRUSTED_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
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
