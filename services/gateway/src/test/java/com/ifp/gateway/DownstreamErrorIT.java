package com.ifp.gateway;

import com.ifp.gateway.support.AbstractGatewayIT;
import com.ifp.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamErrorIT extends AbstractGatewayIT {

    @Test
    void downstreamErrorResponsesPassThroughUnchanged() {
        client().get().uri("/api/invoices/" + UUID.randomUUID() + "/fail")
                .header(AUTHORIZATION, bearer(TestTokens.seller(UUID.randomUUID())))
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody().jsonPath("$.code").isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void slowDownstreamBecomesGatewayTimeoutWithoutInternalDetails() {
        String body = client().get().uri("/api/invoices/" + UUID.randomUUID() + "/slow")
                .header(AUTHORIZATION, bearer(TestTokens.seller(UUID.randomUUID())))
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("DOWNSTREAM_TIMEOUT")
                .doesNotContain("127.0.0.1")
                .doesNotContainIgnoringCase("exception")
                .doesNotContainIgnoringCase("timeout of");
    }
}
