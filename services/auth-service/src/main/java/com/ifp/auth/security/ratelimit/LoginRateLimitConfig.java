package com.ifp.auth.security.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoginRateLimitProperties.class)
public class LoginRateLimitConfig {

    @Bean
    LoginAttemptLimiter loginAttemptLimiter(StringRedisTemplate redis, LoginRateLimitProperties properties) {
        return new LoginAttemptLimiter(redis, properties);
    }

    @Bean
    LoginRateLimitFilter loginRateLimitFilter(LoginAttemptLimiter limiter, LoginRateLimitProperties properties) {
        return new LoginRateLimitFilter(limiter, properties.enabled());
    }

    /** The filter runs inside the security filter chains only; keep Spring Boot from also registering it globally. */
    @Bean
    FilterRegistrationBean<LoginRateLimitFilter> loginRateLimitFilterRegistration(LoginRateLimitFilter filter) {
        FilterRegistrationBean<LoginRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(name = "auth.rate-limit.enabled", matchIfMissing = true)
    LoginOutcomeListener loginOutcomeListener(LoginAttemptLimiter limiter) {
        return new LoginOutcomeListener(limiter);
    }
}
