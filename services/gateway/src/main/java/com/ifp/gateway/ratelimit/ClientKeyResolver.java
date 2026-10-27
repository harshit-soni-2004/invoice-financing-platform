package com.ifp.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Rate-limit key: {@code user:<uuid>} for authenticated requests (the validated token subject, never the raw token),
 * otherwise {@code ip:<sha-256 prefix>} of the client address so raw IPs are not stored in Redis.
 * X-Forwarded-For is only honoured for the configured number of trusted proxy hops; with 0 hops the TCP peer address
 * is used, so a client cannot pick its own bucket by sending the header.
 */
public class ClientKeyResolver implements KeyResolver {

    private final XForwardedRemoteAddressResolver forwardedResolver;

    public ClientKeyResolver(int trustedProxyHops) {
        this.forwardedResolver = trustedProxyHops > 0 ? XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyHops) : null;
    }

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> "user:" + principal.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + hash(clientAddress(exchange))));
    }

    private String clientAddress(ServerWebExchange exchange) {
        InetSocketAddress address = forwardedResolver != null
                ? forwardedResolver.resolve(exchange)
                : exchange.getRequest().getRemoteAddress();
        if (address == null) {
            return "unknown";
        }
        return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
