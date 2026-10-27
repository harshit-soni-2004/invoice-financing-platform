package com.ifp.financing.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifp.financing.domain.OfferStatus;
import com.ifp.financing.repository.FinancingOfferRepository;
import com.ifp.financing.service.OfferService;
import com.ifp.financing.support.AbstractIntegrationTest;
import com.ifp.financing.support.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OfferApiIT extends AbstractIntegrationTest {

    @Autowired
    private FinancingOfferRepository offers;
    @Autowired
    private OfferService offerService;

    @Test
    void financierCreatesOfferOnListedInvoice() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID financier = UUID.randomUUID();
        UUID invoice = listedInvoice(seller);

        mockMvc.perform(withToken(post("/api/financing/offers"), TestJwtSupport.financier(financier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(offerBody(invoice))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("OFFERED"))
                .andExpect(jsonPath("$.sellerId").value(seller.toString()))
                .andExpect(jsonPath("$.financierId").value(financier.toString()))
                .andExpect(jsonPath("$.advanceAmount").value(480000.00))
                .andExpect(jsonPath("$.annualRate").value(0.12))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void opportunityShowsAdvanceCap() throws Exception {
        UUID invoice = listedInvoice(UUID.randomUUID());
        mockMvc.perform(withToken(get("/api/financing/opportunities/{id}", invoice), TestJwtSupport.financier(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxAdvanceAmount").value(490000.00))
                .andExpect(jsonPath("$.daysToMaturity").value(60));
    }

    @Test
    void overfinancingAndInvalidTermsAreRejected() throws Exception {
        UUID invoice = listedInvoice(UUID.randomUUID());
        UUID financier = UUID.randomUUID();

        expectOfferError(financier, with(offerBody(invoice), "advanceAmount", "490000.01"), 422, "ADVANCE_TOO_HIGH");
        expectOfferError(financier, with(offerBody(invoice), "annualRate", "0.3601"), 422, "RATE_OUT_OF_RANGE");
        expectOfferError(financier, with(offerBody(invoice), "annualRate", "0.12345"), 400, "VALIDATION_FAILED");
        expectOfferError(financier, with(offerBody(invoice), "processingFee", "480000.00"), 422, "PROCESSING_FEE_INVALID");
        expectOfferError(financier, with(offerBody(invoice), "installmentCount", 13), 422, "INSTALLMENTS_OUT_OF_RANGE");
        expectOfferError(financier, with(offerBody(invoice), "advanceAmount", "100.001"), 400, "VALIDATION_FAILED");
        expectOfferError(financier, with(offerBody(invoice), "expiresAt", clock.instant().minusSeconds(1).toString()), 422, "EXPIRY_INVALID");
        expectOfferError(financier, with(offerBody(invoice), "expiresAt", clock.instant().plus(Duration.ofDays(15)).toString()), 422, "EXPIRY_INVALID");
        assertThat(offers.count((root, q, cb) -> cb.equal(root.get("invoiceId"), invoice))).isZero();
    }

    @Test
    void offersOnlyForListedInvoicesFarEnoughFromMaturity() throws Exception {
        UUID financier = UUID.randomUUID();
        expectOfferError(financier, offerBody(UUID.randomUUID()), 404, "INVOICE_NOT_FOUND");
        UUID tooSoon = listedInvoice(UUID.randomUUID(), 5);
        expectOfferError(financier, offerBody(tooSoon), 422, "INVOICE_NOT_ELIGIBLE");
    }

    @Test
    void financierCannotHoldTwoOpenOffersOnOneInvoiceButOthersMayCompete() throws Exception {
        UUID invoice = listedInvoice(UUID.randomUUID());
        UUID financier = UUID.randomUUID();
        createOffer(financier, invoice);

        expectOfferError(financier, offerBody(invoice), 409, "DUPLICATE_OPEN_OFFER");
        createOffer(UUID.randomUUID(), invoice);
    }

    @Test
    void visibilityIsScopedToTheParties() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID financierA = UUID.randomUUID();
        UUID financierB = UUID.randomUUID();
        UUID invoice = listedInvoice(seller);
        UUID offerA = createOffer(financierA, invoice);
        UUID offerB = createOffer(financierB, invoice);

        JsonNode sellerView = body(mockMvc.perform(withToken(get("/api/financing/offers").param("invoiceId", invoice.toString()),
                TestJwtSupport.seller(seller))).andExpect(status().isOk()).andReturn());
        assertThat(sellerView.get("content").findValuesAsText("id")).containsExactlyInAnyOrder(offerA.toString(), offerB.toString());

        JsonNode financierView = body(mockMvc.perform(withToken(get("/api/financing/offers").param("invoiceId", invoice.toString()),
                TestJwtSupport.financier(financierA))).andExpect(status().isOk()).andReturn());
        assertThat(financierView.get("content").findValuesAsText("id")).containsExactly(offerA.toString());

        mockMvc.perform(withToken(get("/api/financing/offers/{id}", offerB), TestJwtSupport.financier(financierA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/financing/offers/{id}", offerA), TestJwtSupport.seller(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/financing/offers/{id}", offerA), TestJwtSupport.admin(UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    @Test
    void acceptingCreatesAgreementAndClosesCompetingOffers() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID invoice = listedInvoice(seller);
        UUID winner = createOffer(UUID.randomUUID(), invoice);
        UUID loser = createOffer(UUID.randomUUID(), invoice);

        mockMvc.perform(withToken(post("/api/financing/offers/{id}/accept", winner), TestJwtSupport.seller(seller)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.offerId").value(winner.toString()))
                .andExpect(jsonPath("$.advanceAmount").value(480000.00));

        assertThat(offers.findById(winner).orElseThrow().getStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(offers.findById(loser).orElseThrow().getStatus()).isEqualTo(OfferStatus.CLOSED);

        mockMvc.perform(withToken(post("/api/financing/offers/{id}/accept", loser), TestJwtSupport.seller(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        mockMvc.perform(withToken(post("/api/financing/offers/{id}/accept", winner), TestJwtSupport.seller(seller)))
                .andExpect(status().isConflict());
        expectOfferError(UUID.randomUUID(), offerBody(invoice), 409, "INVOICE_ALREADY_FINANCED");
        mockMvc.perform(withToken(get("/api/financing/opportunities/{id}", invoice), TestJwtSupport.financier(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyTheInvoiceSellerMayAcceptOrReject() throws Exception {
        UUID invoice = listedInvoice(UUID.randomUUID());
        UUID offer = createOffer(UUID.randomUUID(), invoice);

        mockMvc.perform(withToken(post("/api/financing/offers/{id}/accept", offer), TestJwtSupport.seller(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/financing/offers/{id}/reject", offer), TestJwtSupport.seller(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/financing/offers/{id}/accept", offer), TestJwtSupport.financier(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        assertThat(offers.findById(offer).orElseThrow().getStatus()).isEqualTo(OfferStatus.OFFERED);
    }

    @Test
    void sellerRejectsAndFinancierWithdraws() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID financier = UUID.randomUUID();
        UUID invoice = listedInvoice(seller);
        UUID rejected = createOffer(financier, invoice);

        mockMvc.perform(withToken(post("/api/financing/offers/{id}/reject", rejected), TestJwtSupport.seller(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "Rate too high"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReason").value("Rate too high"));

        UUID withdrawn = createOffer(financier, invoice);
        mockMvc.perform(withToken(post("/api/financing/offers/{id}/withdraw", withdrawn), TestJwtSupport.financier(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/financing/offers/{id}/withdraw", withdrawn), TestJwtSupport.financier(financier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        JsonNode history = body(mockMvc.perform(withToken(get("/api/financing/offers/{id}/history", rejected), TestJwtSupport.seller(seller)))
                .andExpect(status().isOk()).andReturn());
        assertThat(history.findValuesAsText("toStatus")).containsExactly("OFFERED", "REJECTED");
        assertThat(history.get(1).get("actorId").isNull()).isTrue();
    }

    @Test
    void expiredOfferCannotBeAcceptedAndIsSweptToExpired() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID invoice = listedInvoice(seller);
        UUID offer = createOffer(UUID.randomUUID(), with(offerBody(invoice), "expiresAt", clock.instant().plus(Duration.ofHours(1)).toString()));

        clock.advance(Duration.ofHours(2));
        mockMvc.perform(withToken(post("/api/financing/offers/{id}/accept", offer), TestJwtSupport.seller(seller)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));

        offerService.expireDueOffers(1000);
        assertThat(offers.findById(offer).orElseThrow().getStatus()).isEqualTo(OfferStatus.EXPIRED);
    }

    @Test
    void listingRejectsUnknownSortFields() throws Exception {
        mockMvc.perform(withToken(get("/api/financing/offers").param("sort", "sellerId,asc"), TestJwtSupport.seller(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY_PARAMETER"));
    }

    private void expectOfferError(UUID financier, Map<String, Object> body, int status, String code) throws Exception {
        mockMvc.perform(withToken(post("/api/financing/offers"), TestJwtSupport.financier(financier))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        body.put(key, value);
        return body;
    }
}
