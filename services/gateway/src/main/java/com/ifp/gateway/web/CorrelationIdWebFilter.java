package com.ifp.gateway.web;

import com.ifp.common.web.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Runs before Spring Security so that rejected requests also carry an ID. A client-supplied ID is kept only if it
 * matches the platform format; anything else is discarded and replaced, never forwarded or logged (prevents log
 * injection). The same ID goes to the downstream service and back to the client.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    public static final String ATTRIBUTE = CorrelationIdWebFilter.class.getName() + ".id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationId.resolve(exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(CorrelationId.HEADER, correlationId))
                .build();
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getAttributes().put(ATTRIBUTE, correlationId);
        // Set just before commit so a downstream echo of the header cannot produce a duplicate.
        mutated.getResponse().beforeCommit(() -> {
            mutated.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);
            return Mono.empty();
        });
        return chain.filter(mutated);
    }
}
