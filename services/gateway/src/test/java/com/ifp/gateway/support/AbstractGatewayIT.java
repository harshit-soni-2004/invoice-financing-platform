package com.ifp.gateway.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.UUID;

/**
 * Real gateway on a random port, real Redis (Testcontainers), routes pointing at {@link DownstreamStub},
 * and token validation against the {@link TestTokens} JWK endpoint. Subclasses share one cached context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1500ms",
        "gateway.cors.allowed-origins=http://localhost:4200"
})
@Import(RedisTestcontainer.class)
public abstract class AbstractGatewayIT {

    @Autowired
    protected WebTestClient webClient;

    @DynamicPropertySource
    static void routesAndTokens(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestTokens::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestTokens.AUDIENCE);
        registry.add("AUTH_SERVICE_URI", DownstreamStub::uri);
        registry.add("INVOICE_SERVICE_URI", DownstreamStub::uri);
        registry.add("FINANCING_SERVICE_URI", DownstreamStub::uri);
        registry.add("NOTIFICATION_SERVICE_URI", DownstreamStub::uri);
        registry.add("AUDIT_SERVICE_URI", DownstreamStub::uri);
    }

    protected WebTestClient client() {
        return webClient.mutate().responseTimeout(Duration.ofSeconds(10)).build();
    }

    /** A path unique to the calling test, so stub recordings never mix between tests. */
    protected static String uniqueInvoicePath() {
        return "/api/invoices/" + UUID.randomUUID();
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected static final String AUTHORIZATION = HttpHeaders.AUTHORIZATION;
}
