package com.ifp.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClientKeyResolverTest {

    @Test
    void authenticatedRequestsAreKeyedByTokenSubject() {
        UUID subject = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("raw-token-value").header("alg", "RS256").subject(subject.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        ServerWebExchange exchange = exchange("203.0.113.7", null).mutate()
                .principal(Mono.just(new JwtAuthenticationToken(jwt, List.of())))
                .build();

        assertThat(new ClientKeyResolver(0).resolve(exchange).block()).isEqualTo("user:" + subject);
    }

    @Test
    void anonymousRequestsAreKeyedByHashedPeerAddress() {
        String key = new ClientKeyResolver(0).resolve(exchange("203.0.113.7", null)).block();

        assertThat(key).isEqualTo("ip:" + ClientKeyResolver.hash("203.0.113.7")).doesNotContain("203.0.113.7");
        assertThat(key).hasSize("ip:".length() + 32);
    }

    @Test
    void forwardedForIsIgnoredWithoutTrustedProxies() {
        String spoofed = new ClientKeyResolver(0).resolve(exchange("203.0.113.7", "198.51.100.1")).block();

        assertThat(spoofed).isEqualTo("ip:" + ClientKeyResolver.hash("203.0.113.7"));
    }

    @Test
    void forwardedForIsHonouredOnlyUpToTheTrustedHop() {
        // One trusted proxy appends the real client; anything further left is client-controlled.
        ServerWebExchange exchange = exchange("10.0.0.2", "6.6.6.6, 198.51.100.1");

        assertThat(new ClientKeyResolver(1).resolve(exchange).block()).isEqualTo("ip:" + ClientKeyResolver.hash("198.51.100.1"));
    }

    private static ServerWebExchange exchange(String peer, String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/auth/register")
                .remoteAddress(new InetSocketAddress(peer, 40000));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return MockServerWebExchange.from(request.build());
    }
}
