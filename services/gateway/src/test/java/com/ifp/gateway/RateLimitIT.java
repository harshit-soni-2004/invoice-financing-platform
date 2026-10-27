package com.ifp.gateway;

import com.ifp.gateway.ratelimit.RedisTokenBucketRateLimiter;
import com.ifp.gateway.support.DownstreamStub;
import com.ifp.gateway.support.RedisTestcontainer;
import com.ifp.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small limits so buckets drain within a test: each request costs 20 tokens, refilled at 1 token/s, so a drained
 * bucket stays empty for the length of the test. Authenticated API: 3 requests per user. Registration: 2 per address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.rate-limit.api.replenish-rate=1",
        "gateway.rate-limit.api.burst-capacity=60",
        "gateway.rate-limit.api.requested-tokens=20",
        "gateway.rate-limit.public-api.replenish-rate=1",
        "gateway.rate-limit.public-api.burst-capacity=40",
        "gateway.rate-limit.public-api.requested-tokens=20",
        "gateway.rate-limit.redis-timeout=300ms",
        "spring.data.redis.timeout=300ms"
})
@Import(RedisTestcontainer.class)
class RateLimitIT {

    @Autowired
    private WebTestClient webClient;

    @Autowired
    private GenericContainer<?> redisContainer;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestTokens::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestTokens.AUDIENCE);
        registry.add("AUTH_SERVICE_URI", DownstreamStub::uri);
        registry.add("INVOICE_SERVICE_URI", DownstreamStub::uri);
    }

    @Test
    void normalTrafficPassesThenExcessIsRejectedPerUser() {
        String alice = TestTokens.seller(UUID.randomUUID());
        String bob = TestTokens.seller(UUID.randomUUID());

        for (int i = 0; i < 3; i++) {
            get(alice).expectStatus().isOk().expectHeader().exists(RedisTokenBucketRateLimiter.REMAINING_HEADER);
        }
        get(alice).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        get(bob).expectStatus().isOk();
    }

    @Test
    void sameUserIsLimitedAcrossDifferentTokens() {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            get(TestTokens.seller(user)).expectStatus().isOk();
        }
        get(TestTokens.seller(user)).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void redisKeysContainNoTokensOrRawAddresses() {
        UUID user = UUID.randomUUID();
        String token = TestTokens.seller(user);
        get(token).expectStatus().isOk();

        List<String> keys = redis.keys("ifp:rate:*").collectList().block(Duration.ofSeconds(5));
        assertThat(keys).contains("ifp:rate:{api:user:" + user + "}.tokens");
        assertThat(keys).noneMatch(key -> key.contains(token) || key.contains("eyJ") || key.contains("127.0.0.1"));
    }

    @Test
    void redisOutageFailsOpenForAuthenticatedApisAndClosedForRegistrationThenRecovers() {
        String exhausted = TestTokens.seller(UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            get(exhausted).expectStatus().isOk();
        }
        get(exhausted).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            // FAIL_OPEN: authenticated traffic continues (identity was still verified), marked as unmetered.
            get(TestTokens.seller(UUID.randomUUID()))
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(RedisTokenBucketRateLimiter.UNAVAILABLE_HEADER, "true");
            // FAIL_CLOSED: unauthenticated registration is refused rather than left unthrottled.
            register().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            // Fail-open never turns into an authentication bypass.
            webClient.get().uri("/api/invoices/" + UUID.randomUUID()).exchange().expectStatus().isUnauthorized();
        } finally {
            docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }

        // Recovery: limits apply again and earlier state is intact.
        awaitRedisDecisions();
        get(exhausted).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        get(TestTokens.seller(UUID.randomUUID()))
                .expectStatus().isOk()
                .expectHeader().doesNotExist(RedisTokenBucketRateLimiter.UNAVAILABLE_HEADER);
    }

    @Test
    void registrationIsLimitedPerClientAddress() {
        // Runs in isolation from the outage test's registration call because that one never reached Redis.
        redis.keys("ifp:rate:{public:*").flatMap(redis::delete).blockLast(Duration.ofSeconds(5));

        register().expectStatus().isOk();
        register().expectStatus().isOk();
        register().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private WebTestClient.ResponseSpec get(String token) {
        return webClient.get().uri("/api/invoices/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + token)
                .exchange();
    }

    private WebTestClient.ResponseSpec register() {
        return webClient.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange();
    }

    private void awaitRedisDecisions() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            boolean metered = get(TestTokens.seller(UUID.randomUUID()))
                    .returnResult(String.class).getResponseHeaders()
                    .getFirst(RedisTokenBucketRateLimiter.UNAVAILABLE_HEADER) == null;
            if (metered) {
                return;
            }
        }
        throw new AssertionError("Rate limiter did not recover after Redis came back");
    }
}
