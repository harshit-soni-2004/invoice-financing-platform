package com.ifp.invoice.web;

import com.ifp.invoice.support.AbstractIntegrationTest;
import com.ifp.invoice.support.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.ifp.invoice.support.TestJwtSupport.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uses real RS256 tokens verified by the production JwtDecoder against the test JWK endpoint. */
class SecurityIT extends AbstractIntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void healthEndpointIsPublicAndDatabaseBacked() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().string("X-Correlation-Id", not(emptyOrNullString())));
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/invoices"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")));
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        mockMvc.perform(withToken(get("/api/invoices"), TestJwtSupport.seller(UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    @Test
    void tokenSignedWithUntrustedKeyIsRejected() throws Exception {
        expectInvalidToken(token().signedByUntrustedKey().sign());
    }

    @Test
    void tokenFromWrongIssuerIsRejected() throws Exception {
        expectInvalidToken(token().issuer("https://evil.example").sign());
    }

    @Test
    void tokenForWrongAudienceIsRejected() throws Exception {
        expectInvalidToken(token().audience("some-other-api").sign());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        // Beyond the default 60-second clock-skew allowance.
        expectInvalidToken(token().expiresAt(Instant.now().minusSeconds(300)).sign());
    }

    @Test
    void symmetricallySignedTokenIsRejected() throws Exception {
        expectInvalidToken(token().signHs256("a-shared-secret-that-is-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        expectInvalidToken("not-a-jwt");
    }

    @Test
    void tokenWhoseSubjectIsNotAUserIdIsRejected() throws Exception {
        expectInvalidToken(token().subject("admin").roles("ADMIN").sign());
    }

    @Test
    void financierCannotCreateOrListInvoices() throws Exception {
        String financier = TestJwtSupport.financier(UUID.randomUUID());
        mockMvc.perform(withToken(post("/api/invoices"), financier)
                        .contentType(MediaType.APPLICATION_JSON).content(json(invoiceBody(uniqueNumber()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(withToken(get("/api/invoices"), financier)).andExpect(status().isForbidden());
    }

    @Test
    void tokenWithoutRolesIsForbidden() throws Exception {
        mockMvc.perform(withToken(get("/api/invoices"), token().withoutRoles().sign()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownRoleGrantsNothing() throws Exception {
        mockMvc.perform(withToken(get("/api/invoices"), token().roles("SUPERUSER").sign()))
                .andExpect(status().isForbidden());
    }

    @Test
    void sellerCannotPerformVerificationActions() throws Exception {
        UUID sellerId = UUID.randomUUID();
        UUID invoiceId = createInvoice(sellerId);
        String seller = TestJwtSupport.seller(sellerId);
        mockMvc.perform(withToken(post("/api/invoices/{id}/submit", invoiceId), seller)).andExpect(status().isOk());

        mockMvc.perform(withToken(post("/api/invoices/{id}/buyer-confirmation", invoiceId), seller)).andExpect(status().isForbidden());
        mockMvc.perform(withToken(post("/api/invoices/{id}/verify", invoiceId), seller)).andExpect(status().isForbidden());
        mockMvc.perform(withToken(post("/api/invoices/{id}/reject", invoiceId), seller)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"self-reject\"}"))
                .andExpect(status().isForbidden());

        assertThat(getInvoice(invoiceId, seller).get("status").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    void adminCannotActAsSeller() throws Exception {
        UUID invoiceId = createInvoice(UUID.randomUUID());
        mockMvc.perform(withToken(post("/api/invoices/{id}/submit", invoiceId), TestJwtSupport.admin(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void sellerCannotReadOrModifyAnotherSellersInvoice() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID invoiceId = createInvoice(ownerId);
        String intruder = TestJwtSupport.seller(UUID.randomUUID());

        mockMvc.perform(withToken(get("/api/invoices/{id}", invoiceId), intruder))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND"));
        mockMvc.perform(withToken(get("/api/invoices/{id}/history", invoiceId), intruder)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/invoices/{id}/submit", invoiceId), intruder)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/invoices/{id}/cancel", invoiceId), intruder)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(put("/api/invoices/{id}", invoiceId), intruder)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json(invoiceBody("HIJACK-1"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/invoices"), intruder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", not(org.hamcrest.Matchers.hasItem(invoiceId.toString()))));

        var owned = getInvoice(invoiceId, TestJwtSupport.seller(ownerId));
        assertThat(owned.get("status").asText()).isEqualTo("DRAFT");
        assertThat(owned.get("version").asLong()).isZero();
    }

    @Test
    void adminCanReadAnySellersInvoice() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID invoiceId = createInvoice(ownerId);
        String admin = TestJwtSupport.admin(UUID.randomUUID());

        assertThat(getInvoice(invoiceId, admin).get("sellerId").asText()).isEqualTo(ownerId.toString());
        mockMvc.perform(withToken(get("/api/invoices").param("sellerId", ownerId.toString()), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(invoiceId.toString()));
    }

    @Test
    void everyApplicationEndpointDeclaresAuthorization() {
        List<HandlerMethod> appHandlers = handlerMapping.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().getPackageName().startsWith("com.ifp.invoice"))
                .toList();

        assertThat(appHandlers).isNotEmpty();
        assertThat(appHandlers).allSatisfy(handler ->
                assertThat(handler.hasMethodAnnotation(PreAuthorize.class))
                        .as("%s must declare @PreAuthorize", handler.getShortLogMessage())
                        .isTrue());
    }

    private void expectInvalidToken(String jwt) throws Exception {
        mockMvc.perform(withToken(get("/api/invoices"), jwt))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
    }
}
