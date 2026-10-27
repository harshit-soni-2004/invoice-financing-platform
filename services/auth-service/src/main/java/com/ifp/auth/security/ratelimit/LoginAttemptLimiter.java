package com.ifp.auth.security.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed counters for the login and token endpoints. Keys contain only a SHA-256 digest, so neither the
 * client address nor the username is stored in readable form. Every operation fails open: if Redis is unavailable
 * the request is allowed and a warning is logged, because a Redis outage must not lock every user out.
 */
public class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);
    private static final String PREFIX = "auth:rl:";

    private static final RedisScript<Long> COUNT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return count""", Long.class);

    private static final RedisScript<Long> FAILURE = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if count >= tonumber(ARGV[2]) then
              redis.call('SET', KEYS[2], '1', 'PX', ARGV[3])
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0""", Long.class);

    private final StringRedisTemplate redis;
    private final LoginRateLimitProperties properties;

    public LoginAttemptLimiter(StringRedisTemplate redis, LoginRateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Counts one request; returns how long to wait if the client address exceeded the limit for this endpoint. */
    public Optional<Duration> checkRequestRate(String endpoint, String clientAddress) {
        String key = PREFIX + "req:" + digest(endpoint, clientAddress);
        try {
            Long count = redis.execute(COUNT, List.of(key), Long.toString(properties.ipWindow().toMillis()));
            if (count == null || count <= properties.ipRequests()) {
                return Optional.empty();
            }
            return Optional.of(remaining(key, properties.ipWindow()));
        } catch (RuntimeException e) {
            return unavailable(e);
        }
    }

    /** Returns how long to wait if this client address + username combination is locked out. */
    public Optional<Duration> checkLockout(String clientAddress, String username) {
        String key = lockKey(clientAddress, username);
        try {
            Long millis = redis.getExpire(key, TimeUnit.MILLISECONDS);
            return millis != null && millis > 0 ? Optional.of(Duration.ofMillis(millis)) : Optional.empty();
        } catch (RuntimeException e) {
            return unavailable(e);
        }
    }

    public void recordFailure(String clientAddress, String username) {
        String id = digest(clientAddress, normalize(username));
        try {
            Long locked = redis.execute(FAILURE, List.of(PREFIX + "fail:" + id, PREFIX + "lock:" + id),
                    Long.toString(properties.failureWindow().toMillis()), Integer.toString(properties.maxFailures()),
                    Long.toString(properties.lockout().toMillis()));
            if (locked != null && locked == 1) {
                log.info("Login locked out for {} after {} failed attempts", properties.lockout(), properties.maxFailures());
            }
        } catch (RuntimeException e) {
            unavailable(e);
        }
    }

    public void recordSuccess(String clientAddress, String username) {
        try {
            redis.delete(PREFIX + "fail:" + digest(clientAddress, normalize(username)));
        } catch (RuntimeException e) {
            unavailable(e);
        }
    }

    private Duration remaining(String key, Duration fallback) {
        Long millis = redis.getExpire(key, TimeUnit.MILLISECONDS);
        return millis != null && millis > 0 ? Duration.ofMillis(millis) : fallback;
    }

    private static String lockKey(String clientAddress, String username) {
        return PREFIX + "lock:" + digest(clientAddress, normalize(username));
    }

    private static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static Optional<Duration> unavailable(RuntimeException e) {
        log.warn("Login rate limiter unavailable ({}); allowing the request", e.getClass().getSimpleName());
        return Optional.empty();
    }

    private static String digest(String first, String second) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(first.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) 0);
            sha256.update(second.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
