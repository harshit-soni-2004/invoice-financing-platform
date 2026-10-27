package com.ifp.gateway;

import com.ifp.gateway.support.RedisTestcontainer;
import com.ifp.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Routes point at a port where nothing listens, as when a service is down. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RedisTestcontainer.class)
class DownstreamUnavailableIT {

    private static final String CLOSED_PORT_URI = "http://127.0.0.1:" + freePort();

    @Autowired
    private WebTestClient webClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestTokens::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestTokens.AUDIENCE);
        registry.add("AUTH_SERVICE_URI", () -> CLOSED_PORT_URI);
        registry.add("INVOICE_SERVICE_URI", () -> CLOSED_PORT_URI);
    }

    @Test
    void unreachableServiceBecomes503WithoutRevealingWhereItWas() {
        String body = webClient.mutate().responseTimeout(Duration.ofSeconds(15)).build()
                .get().uri("/api/invoices/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + TestTokens.seller(UUID.randomUUID()))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("DOWNSTREAM_UNAVAILABLE")
                .doesNotContain("127.0.0.1")
                .doesNotContain(CLOSED_PORT_URI.substring(CLOSED_PORT_URI.lastIndexOf(':') + 1) + "\"")
                .doesNotContainIgnoringCase("connection refused");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
