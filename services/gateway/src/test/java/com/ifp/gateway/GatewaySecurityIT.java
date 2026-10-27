package com.ifp.gateway;

import com.ifp.gateway.support.AbstractGatewayIT;
import com.ifp.gateway.support.DownstreamStub;
import com.ifp.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySecurityIT extends AbstractGatewayIT {

    @Test
    void validTokenIsRoutedWithTheOriginalAuthorizationHeaderAndNoInventedIdentityHeaders() {
        String path = uniqueInvoicePath();
        String token = TestTokens.seller(UUID.randomUUID());

        client().get().uri(path).header(AUTHORIZATION, bearer(token))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"0\"");

        List<DownstreamStub.Received> received = DownstreamStub.receivedFor(path);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).header(HttpHeaders.AUTHORIZATION)).isEqualTo(bearer(token));
        assertThat(received.get(0).headerNames())
                .noneMatch(name -> name.startsWith("x-user") || name.startsWith("x-auth") || name.equals("x-roles"));
    }

    @Test
    void healthIsPublic() {
        client().get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        client().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client().get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    void missingTokenIsRejectedAtTheEdge() {
        String path = uniqueInvoicePath();

        client().get().uri(path).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");

        assertThat(DownstreamStub.receivedFor(path)).isEmpty();
    }

    @Test
    void invalidTokensAreRejectedWithoutLeakingDetails() {
        UUID user = UUID.randomUUID();
        List<String> authorizationHeaders = List.of(
                "Bearer not-a-jwt",
                "Bearer " + "a.b.c",
                "Basic dXNlcjpwYXNz",
                bearer(TestTokens.token().subject(user.toString()).expiresAt(Instant.now().minusSeconds(120)).sign()),
                bearer(TestTokens.token().subject(user.toString()).withoutExpiry().sign()),
                bearer(TestTokens.token().subject(user.toString()).signedWith(TestTokens.ATTACKER_KEY).sign()),
                bearer(TestTokens.token().subject(user.toString()).signHs256()),
                bearer(TestTokens.token().subject(user.toString()).issuer("http://evil.example").sign()),
                bearer(TestTokens.token().subject(user.toString()).audience("another-api").sign()),
                bearer(TestTokens.token().subject("asha@example.test").sign()));

        for (String header : authorizationHeaders) {
            String path = uniqueInvoicePath();
            String body = client().get().uri(path).header(AUTHORIZATION, header)
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody(String.class).returnResult().getResponseBody();

            assertThat(body).as("error body for %s", header.substring(0, Math.min(12, header.length())))
                    .contains("UNAUTHENTICATED")
                    .doesNotContain(header.substring(header.indexOf(' ') + 1))
                    .doesNotContainIgnoringCase("exception")
                    .doesNotContainIgnoringCase("jwt");
            assertThat(DownstreamStub.receivedFor(path)).isEmpty();
        }
    }

    @Test
    void unknownSigningKeyIsRejectedAndRotationIsPickedUpWithoutRestart() {
        String path = uniqueInvoicePath();
        String nextKeyToken = TestTokens.token().signedWith(TestTokens.NEXT_KEY).sign();
        try {
            client().get().uri(path).header(AUTHORIZATION, bearer(nextKeyToken)).exchange().expectStatus().isUnauthorized();

            // auth-service rotates: publishes the new key next to the old one.
            TestTokens.publish(TestTokens.PRIMARY_KEY, TestTokens.NEXT_KEY);

            client().get().uri(path).header(AUTHORIZATION, bearer(nextKeyToken)).exchange().expectStatus().isOk();
            client().get().uri(path).header(AUTHORIZATION, bearer(TestTokens.seller(UUID.randomUUID())))
                    .exchange().expectStatus().isOk();
        } finally {
            TestTokens.publish(TestTokens.PRIMARY_KEY);
        }
    }

    @Test
    void sellerCannotReachAdminRoutesButAdminCan() {
        client().get().uri("/api/admin/users").header(AUTHORIZATION, bearer(TestTokens.seller(UUID.randomUUID())))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("ACCESS_DENIED");
        int beforeAdmin = DownstreamStub.receivedFor("/api/admin/users").size();

        client().get().uri("/api/admin/users").header(AUTHORIZATION, bearer(TestTokens.admin(UUID.randomUUID())))
                .exchange().expectStatus().isOk();

        assertThat(DownstreamStub.receivedFor("/api/admin/users")).hasSize(beforeAdmin + 1);
    }

    @Test
    void financingRoutesRequireTokenAndForwardIdempotencyKey() {
        String path = "/api/financing/agreements/" + UUID.randomUUID() + "/disburse";

        client().post().uri(path).header("Idempotency-Key", "key-12345678").exchange().expectStatus().isUnauthorized();
        assertThat(DownstreamStub.receivedFor(path)).isEmpty();

        client().post().uri(path)
                .header(AUTHORIZATION, bearer(TestTokens.financier(UUID.randomUUID())))
                .header("Idempotency-Key", "key-12345678")
                .exchange().expectStatus().isOk();

        List<DownstreamStub.Received> received = DownstreamStub.receivedFor(path);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).header("Idempotency-Key")).isEqualTo("key-12345678");
    }

    @Test
    void notificationsNeedATokenAndAuditEventsNeedAdmin() {
        client().get().uri("/api/notifications").exchange().expectStatus().isUnauthorized();
        int beforeNotifications = DownstreamStub.receivedFor("/api/notifications").size();
        client().get().uri("/api/notifications").header(AUTHORIZATION, bearer(TestTokens.seller(UUID.randomUUID())))
                .exchange().expectStatus().isOk();
        assertThat(DownstreamStub.receivedFor("/api/notifications")).hasSize(beforeNotifications + 1);

        int beforeAudit = DownstreamStub.receivedFor("/api/audit-events").size();
        client().get().uri("/api/audit-events").header(AUTHORIZATION, bearer(TestTokens.financier(UUID.randomUUID())))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("ACCESS_DENIED");
        assertThat(DownstreamStub.receivedFor("/api/audit-events")).hasSize(beforeAudit);
        client().get().uri("/api/audit-events").header(AUTHORIZATION, bearer(TestTokens.admin(UUID.randomUUID())))
                .exchange().expectStatus().isOk();
        assertThat(DownstreamStub.receivedFor("/api/audit-events")).hasSize(beforeAudit + 1);
    }

    @Test
    void registrationIsPublicAndRoutedToAuthService() {
        int before = DownstreamStub.receivedFor("/api/auth/register").size();

        client().post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk();

        assertThat(DownstreamStub.receivedFor("/api/auth/register")).hasSize(before + 1);
    }

    @Test
    void unroutedPathsAreNeverExposed() {
        client().get().uri("/internal/anything").exchange().expectStatus().isUnauthorized();
        client().get().uri("/actuator/env").header(AUTHORIZATION, bearer(TestTokens.admin(UUID.randomUUID())))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/api/unknown").header(AUTHORIZATION, bearer(TestTokens.seller(UUID.randomUUID())))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }
}
