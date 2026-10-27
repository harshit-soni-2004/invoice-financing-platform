package com.ifp.financing.web;

import com.ifp.financing.support.AbstractIntegrationTest;
import com.ifp.financing.support.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIT extends AbstractIntegrationTest {

    private static final String OFFERS = "/api/financing/offers";

    @Test
    void healthIsPublicButApiRequiresToken() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get(OFFERS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/financing/agreements")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/financing/opportunities")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTokensAreRejected() throws Exception {
        String subject = UUID.randomUUID().toString();
        expectUnauthorized(TestJwtSupport.token().subject(subject).roles("SELLER").signedByUntrustedKey().sign());
        expectUnauthorized(TestJwtSupport.token().subject(subject).roles("SELLER")
                .signHs256("an-hs256-secret-that-is-long-enough-for-the-algorithm".getBytes(StandardCharsets.UTF_8)));
        expectUnauthorized(TestJwtSupport.token().subject(subject).roles("SELLER").audience("other-api").sign());
        expectUnauthorized(TestJwtSupport.token().subject(subject).roles("SELLER").issuer("http://evil.example").sign());
        expectUnauthorized(TestJwtSupport.token().subject(subject).roles("SELLER").expiresAt(Instant.now().minusSeconds(120)).sign());
        expectUnauthorized(TestJwtSupport.token().subject("not-a-uuid").roles("SELLER").sign());
        expectUnauthorized("not.a.jwt");
    }

    @Test
    void rolesAreEnforcedPerEndpoint() throws Exception {
        UUID invoice = listedInvoice(UUID.randomUUID());
        String body = json(offerBody(invoice));

        mockMvc.perform(withToken(post(OFFERS), TestJwtSupport.seller(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(withToken(post(OFFERS), TestJwtSupport.admin(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(withToken(post(OFFERS), TestJwtSupport.token().subject(UUID.randomUUID().toString()).withoutRoles().sign())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(withToken(get("/api/financing/opportunities"), TestJwtSupport.seller(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(get(OFFERS), TestJwtSupport.token().subject(UUID.randomUUID().toString()).withoutRoles().sign()))
                .andExpect(status().isForbidden());
    }

    @Test
    void clientCannotChooseTheFinancierIdentity() throws Exception {
        UUID invoice = listedInvoice(UUID.randomUUID());
        UUID realFinancier = UUID.randomUUID();
        var body = offerBody(invoice);
        body.put("financierId", UUID.randomUUID().toString());
        body.put("sellerId", UUID.randomUUID().toString());

        var result = mockMvc.perform(withToken(post(OFFERS), TestJwtSupport.financier(realFinancier))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated()).andReturn();

        org.assertj.core.api.Assertions.assertThat(body(result).get("financierId").asText()).isEqualTo(realFinancier.toString());
    }

    private void expectUnauthorized(String token) throws Exception {
        mockMvc.perform(withToken(get(OFFERS), token)).andExpect(status().isUnauthorized());
    }
}
