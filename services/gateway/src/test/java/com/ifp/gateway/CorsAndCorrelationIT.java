package com.ifp.gateway;

import com.ifp.gateway.support.AbstractGatewayIT;
import com.ifp.gateway.support.DownstreamStub;
import com.ifp.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorsAndCorrelationIT extends AbstractGatewayIT {

    private static final String SPA = "http://localhost:4200";

    @Test
    void preflightFromConfiguredOriginIsAllowedWithoutCredentials() {
        client().options().uri("/api/invoices")
                .header(HttpHeaders.ORIGIN, SPA)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,if-match,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SPA)
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, h -> assertThat(h.toLowerCase()).contains("if-match"));
    }

    @Test
    void preflightFromOtherOriginsOrForUnlistedMethodsIsRejected() {
        client().options().uri("/api/invoices")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);

        client().options().uri("/api/invoices")
                .header(HttpHeaders.ORIGIN, SPA)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void actualCrossOriginResponseExposesEtagOnlyToTheConfiguredOrigin() {
        client().get().uri(uniqueInvoicePath())
                .header(HttpHeaders.ORIGIN, SPA)
                .header(AUTHORIZATION, bearer(TestTokens.seller(UUID.randomUUID())))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, SPA)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, h -> assertThat(h).contains("ETag"));

        client().get().uri(uniqueInvoicePath())
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(AUTHORIZATION, bearer(TestTokens.seller(UUID.randomUUID())))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void validCorrelationIdIsPropagatedDownstreamAndReturnedOnce() {
        String path = uniqueInvoicePath();
        String correlationId = "trace-" + UUID.randomUUID();

        List<String> returned = client().get().uri(path)
                .header("X-Correlation-Id", correlationId)
                .header(AUTHORIZATION, bearer(TestTokens.seller(UUID.randomUUID())))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().get("X-Correlation-Id");

        assertThat(returned).containsExactly(correlationId);
        assertThat(DownstreamStub.receivedFor(path).get(0).header("X-Correlation-Id")).isEqualTo(correlationId);
    }

    @Test
    void malformedCorrelationIdIsReplacedNotForwarded() {
        String path = uniqueInvoicePath();

        String returned = client().get().uri(path)
                .header("X-Correlation-Id", "bad id with spaces <script>")
                .header(AUTHORIZATION, bearer(TestTokens.seller(UUID.randomUUID())))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");

        assertThat(returned).matches("[A-Za-z0-9._-]{8,64}").doesNotContain("script");
        String forwarded = DownstreamStub.receivedFor(path).get(0).header("X-Correlation-Id");
        assertThat(forwarded).isEqualTo(returned);
    }

    @Test
    void rejectedRequestsStillGetACorrelationId() {
        client().method(HttpMethod.GET).uri(uniqueInvoicePath())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value("X-Correlation-Id", id -> assertThat(id).matches("[A-Za-z0-9._-]{8,64}"));
    }
}
