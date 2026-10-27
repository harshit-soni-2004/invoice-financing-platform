package com.ifp.financing.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifp.financing.domain.InvoiceSnapshot;
import com.ifp.financing.domain.InvoiceState;
import com.ifp.financing.service.InvoiceProjectionService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared configuration for all integration tests, so they reuse one cached Spring context and one MySQL container.
 * Tests isolate their data with fresh random user and invoice IDs instead of truncating tables.
 */
@SpringBootTest
@TestPropertySource(properties = {"financing.offer-expiry-sweep-enabled=false", "financing.repayment-reminder-sweep-enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    protected static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    protected static final BigDecimal INVOICE_AMOUNT = new BigDecimal("500000.00");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected InvoiceProjectionService projection;

    @Autowired
    protected MutableClock clock;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestJwtSupport::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwtSupport.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestJwtSupport.AUDIENCE);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    protected LocalDate today() {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE));
    }

    protected static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    // ---- Fixtures ----

    protected InvoiceSnapshot snapshot(UUID invoiceId, UUID sellerId, InvoiceState status, long sourceVersion, int daysToDue) {
        return new InvoiceSnapshot(invoiceId, sellerId, "INV-" + invoiceId.toString().substring(0, 8).toUpperCase(),
                "Acme Buyers Pvt Ltd", INVOICE_AMOUNT, "INR", today().minusDays(1), today().plusDays(daysToDue), status,
                sourceVersion, Instant.now());
    }

    /** An invoice listed for financing, due in 60 days. */
    protected UUID listedInvoice(UUID sellerId) {
        return listedInvoice(sellerId, 60);
    }

    protected UUID listedInvoice(UUID sellerId, int daysToDue) {
        UUID invoiceId = UUID.randomUUID();
        projection.apply(snapshot(invoiceId, sellerId, InvoiceState.FINANCING_AVAILABLE, 3, daysToDue));
        return invoiceId;
    }

    protected Map<String, Object> offerBody(UUID invoiceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("invoiceId", invoiceId);
        body.put("advanceAmount", "480000.00");
        body.put("annualRate", "0.1200");
        body.put("processingFee", "1000.00");
        body.put("installmentCount", 2);
        body.put("expiresAt", clock.instant().plus(Duration.ofDays(3)).toString());
        return body;
    }

    protected UUID createOffer(UUID financierId, UUID invoiceId) throws Exception {
        return createOffer(financierId, offerBody(invoiceId));
    }

    protected UUID createOffer(UUID financierId, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(withToken(post("/api/financing/offers"), TestJwtSupport.financier(financierId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(body(result).get("id").asText());
    }

    protected UUID acceptOffer(UUID sellerId, UUID offerId) throws Exception {
        MvcResult result = mockMvc.perform(withToken(post("/api/financing/offers/{id}/accept", offerId), TestJwtSupport.seller(sellerId)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(body(result).get("id").asText());
    }

    protected void approve(UUID financierId, UUID agreementId) throws Exception {
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/approve", agreementId), TestJwtSupport.financier(financierId)))
                .andExpect(status().isOk());
    }

    protected MvcResult disburse(UUID financierId, UUID agreementId, String key) throws Exception {
        return mockMvc.perform(withToken(post("/api/financing/agreements/{id}/disburse", agreementId), TestJwtSupport.financier(financierId))
                        .header("Idempotency-Key", key))
                .andReturn();
    }

    protected MvcResult repay(UUID sellerId, UUID agreementId, String amount, String key) throws Exception {
        return mockMvc.perform(withToken(post("/api/financing/agreements/{id}/repayments", agreementId), TestJwtSupport.seller(sellerId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("amount", amount))))
                .andReturn();
    }

    /** Parties and IDs of an agreement walked to a given point. */
    public record Deal(UUID sellerId, UUID financierId, UUID invoiceId, UUID offerId, UUID agreementId) {
    }

    protected Deal pendingDeal() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID financier = UUID.randomUUID();
        UUID invoice = listedInvoice(seller);
        UUID offer = createOffer(financier, invoice);
        UUID agreement = acceptOffer(seller, offer);
        return new Deal(seller, financier, invoice, offer, agreement);
    }

    protected Deal approvedDeal() throws Exception {
        Deal deal = pendingDeal();
        approve(deal.financierId(), deal.agreementId());
        return deal;
    }

    protected Deal disbursedDeal() throws Exception {
        Deal deal = approvedDeal();
        MvcResult result = disburse(deal.financierId(), deal.agreementId(), newKey());
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("Disbursement failed: " + result.getResponse().getContentAsString());
        }
        return deal;
    }
}
