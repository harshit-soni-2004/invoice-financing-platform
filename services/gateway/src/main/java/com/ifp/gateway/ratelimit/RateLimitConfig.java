package com.ifp.gateway.ratelimit;

import com.ifp.gateway.config.GatewaySettings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/** Routes reference these beans by name ({@code #{@publicRateLimiter}}, {@code #{@apiRateLimiter}}) in application.yml. */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    private static final String SCRIPT_BEAN = "redisRequestRateLimiterScript";

    @Bean
    RedisTokenBucketRateLimiter publicRateLimiter(GatewaySettings settings, ReactiveStringRedisTemplate redis,
                                                 @Qualifier(SCRIPT_BEAN) RedisScript<List<Long>> script) {
        return new RedisTokenBucketRateLimiter("public", settings.rateLimit().publicApi(),
                settings.rateLimit().redisTimeout(), redis, script);
    }

    /** Primary only so the RequestRateLimiter filter factory has an unambiguous default; every route names its limiter. */
    @Bean
    @Primary
    RedisTokenBucketRateLimiter apiRateLimiter(GatewaySettings settings, ReactiveStringRedisTemplate redis,
                                              @Qualifier(SCRIPT_BEAN) RedisScript<List<Long>> script) {
        return new RedisTokenBucketRateLimiter("api", settings.rateLimit().api(), settings.rateLimit().redisTimeout(),
                redis, script);
    }

    @Bean
    ClientKeyResolver clientKeyResolver(GatewaySettings settings) {
        return new ClientKeyResolver(settings.clientIp().trustedProxyHops());
    }
}
