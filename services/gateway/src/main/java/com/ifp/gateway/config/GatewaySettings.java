package com.ifp.gateway.config;

import com.ifp.gateway.ratelimit.FailureMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties("gateway")
public record GatewaySettings(@Valid @NotNull Cors cors, @Valid @NotNull RateLimit rateLimit, @Valid @NotNull ClientIp clientIp) {

    /** Bearer tokens are sent in a header, never as cookies, so credentials are not allowed cross-origin. */
    public record Cors(
            @NotEmpty List<String> allowedOrigins,
            @NotEmpty List<String> allowedMethods,
            @NotEmpty List<String> allowedHeaders,
            @DefaultValue List<String> exposedHeaders,
            @NotNull Duration maxAge) {

        @AssertTrue(message = "gateway.cors.allowed-origins must list explicit origins; '*' is not allowed")
        public boolean isExplicitOrigins() {
            return allowedOrigins == null || allowedOrigins.stream().noneMatch(origin -> origin.contains("*"));
        }
    }

    /**
     * @param publicApi     unauthenticated endpoints (registration); keyed by client network address
     * @param api           authenticated endpoints; keyed by the token subject
     * @param redisTimeout  upper bound on a rate-limit decision before the failure mode applies
     */
    public record RateLimit(@Valid @NotNull Limit publicApi, @Valid @NotNull Limit api, @NotNull Duration redisTimeout) {
    }

    /**
     * Token bucket: {@code replenishRate} tokens per second up to {@code burstCapacity}; each request costs
     * {@code requestedTokens}. Example: 1/s, capacity 60, cost 12 = bursts of 5 and 5 requests per minute sustained.
     */
    public record Limit(
            @Min(1) int replenishRate,
            @Min(1) int burstCapacity,
            @Min(1) int requestedTokens,
            @NotNull FailureMode failureMode) {

        @AssertTrue(message = "burst-capacity must be at least requested-tokens, otherwise no request can ever pass")
        public boolean isSatisfiable() {
            return burstCapacity >= requestedTokens;
        }
    }

    /**
     * @param trustedProxyHops number of reverse proxies in front of the gateway whose X-Forwarded-For entries are
     *                         trusted. 0 (default) uses the TCP peer address, so clients cannot spoof their key.
     */
    public record ClientIp(@Min(0) int trustedProxyHops) {
    }
}
