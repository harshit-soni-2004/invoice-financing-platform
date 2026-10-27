package com.ifp.auth.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginAttemptLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(redis,
            new LoginRateLimitProperties(true, 3, Duration.ofMinutes(1), 5, Duration.ofMinutes(15), Duration.ofMinutes(5)));

    @Test
    @SuppressWarnings("unchecked")
    void requestsOverTheLimitMustWaitForTheWindowToExpire() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(3L, 4L);
        when(redis.getExpire(anyString(), eq(TimeUnit.MILLISECONDS))).thenReturn(42_000L);

        assertThat(limiter.checkRequestRate("/login", "203.0.113.1")).isEmpty();
        assertThat(limiter.checkRequestRate("/login", "203.0.113.1")).contains(Duration.ofSeconds(42));
    }

    @Test
    void lockedCombinationReportsTheRemainingLockout() {
        when(redis.getExpire(anyString(), eq(TimeUnit.MILLISECONDS))).thenReturn(90_000L, -2L);

        assertThat(limiter.checkLockout("203.0.113.1", "a@example.test")).contains(Duration.ofSeconds(90));
        assertThat(limiter.checkLockout("203.0.113.1", "a@example.test")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsOpenWhenRedisIsUnavailable() {
        RedisConnectionFailureException down = new RedisConnectionFailureException("down");
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(down);
        when(redis.getExpire(anyString(), eq(TimeUnit.MILLISECONDS))).thenThrow(down);
        when(redis.delete(anyString())).thenThrow(down);

        assertThat(limiter.checkRequestRate("/oauth2/token", "203.0.113.1")).isEmpty();
        assertThat(limiter.checkLockout("203.0.113.1", "a@example.test")).isEmpty();
        assertThatCode(() -> limiter.recordFailure("203.0.113.1", "a@example.test")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.recordSuccess("203.0.113.1", "a@example.test")).doesNotThrowAnyException();
    }

    @Test
    void usernameCaseAndSpacingMapToOneOpaqueKey() {
        List<String> keys = new ArrayList<>();
        when(redis.getExpire(anyString(), eq(TimeUnit.MILLISECONDS))).thenAnswer(invocation -> {
            keys.add(invocation.getArgument(0));
            return -2L;
        });

        limiter.checkLockout("203.0.113.1", "a@example.test");
        limiter.checkLockout("203.0.113.1", " A@Example.TEST ");
        limiter.checkLockout("203.0.113.1", "b@example.test");

        assertThat(keys.get(0)).isEqualTo(keys.get(1)).isNotEqualTo(keys.get(2))
                .doesNotContain("example").doesNotContain("203.0.113.1");
    }
}
