package com.ifp.gateway.ratelimit;

import com.ifp.gateway.config.GatewaySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Token-bucket limiter using Spring Cloud Gateway's own Lua script, with an explicit {@link FailureMode}.
 * The stock {@code RedisRateLimiter} always allows traffic when Redis fails; here that is a per-route decision.
 */
public class RedisTokenBucketRateLimiter implements RateLimiter<Object> {

    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    public static final String UNAVAILABLE_HEADER = "X-RateLimit-Unavailable";
    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    private final String name;
    private final GatewaySettings.Limit limit;
    private final Duration timeout;
    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<List<Long>> script;

    public RedisTokenBucketRateLimiter(String name, GatewaySettings.Limit limit, Duration timeout,
                                       ReactiveStringRedisTemplate redis, RedisScript<List<Long>> script) {
        this.name = name;
        this.limit = limit;
        this.timeout = timeout;
        this.redis = redis;
        this.script = script;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        // Hash tag {...} keeps both keys in one Redis Cluster slot. The id is already non-sensitive (see ClientKeyResolver).
        String prefix = "ifp:rate:{" + name + ":" + id + "}";
        List<String> keys = List.of(prefix + ".tokens", prefix + ".timestamp");
        List<String> args = List.of(String.valueOf(limit.replenishRate()), String.valueOf(limit.burstCapacity()), "",
                String.valueOf(limit.requestedTokens()));
        return redis.execute(script, keys, args)
                .reduce(new ArrayList<Long>(), (all, part) -> {
                    all.addAll(part);
                    return all;
                })
                .timeout(timeout)
                .map(result -> new Response(result.get(0) == 1L,
                        Map.of(REMAINING_HEADER, String.valueOf(result.get(1) / limit.requestedTokens()))))
                .onErrorResume(error -> {
                    log.warn("Rate limiter '{}' unavailable ({}); applying {}", name, error.getClass().getSimpleName(),
                            limit.failureMode());
                    return Mono.just(new Response(limit.failureMode() == FailureMode.FAIL_OPEN,
                            Map.of(UNAVAILABLE_HEADER, "true")));
                });
    }

    @Override
    public Map<String, Object> getConfig() {
        return Collections.emptyMap();
    }

    @Override
    public Class<Object> getConfigClass() {
        return Object.class;
    }

    @Override
    public Object newConfig() {
        return new Object();
    }
}
