package com.ifp.financing.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifp.financing.repository.DisbursementRepository;
import com.ifp.financing.repository.RepaymentAllocationRepository;
import com.ifp.financing.support.AbstractIntegrationTest;
import com.ifp.financing.support.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgreementFlowIT extends AbstractIntegrationTest {

    // 480000.00 x 0.12 x 60 / 365 = 9468.49, plus the 1000.00 processing fee.
    private static final BigDecimal FEE = new BigDecimal("10468.49");
    private static final BigDecimal TOTAL = new BigDecimal("490468.49");

    @Autowired
    private DisbursementRepository disbursements;
    @Autowired
    private RepaymentAllocationRepository allocations;

    @Test
    void onlyTheOwningFinancierApproves() throws Exception {
        Deal deal = pendingDeal();

        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/approve", deal.agreementId()), TestJwtSupport.financier(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/approve", deal.agreementId()), TestJwtSupport.seller(deal.sellerId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/approve", deal.agreementId()), TestJwtSupport.financier(deal.financierId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/approve", deal.agreementId()), TestJwtSupport.financier(deal.financierId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void pendingAgreementCannotBeDisbursed() throws Exception {
        Deal deal = pendingDeal();

        MvcResult result = disburse(deal.financierId(), deal.agreementId(), newKey());

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result).get("code").asText()).isEqualTo("INVALID_STATUS_TRANSITION");
        assertThat(disbursements.findByAgreementId(deal.agreementId())).isEmpty();
    }

    @Test
    void disbursementIsIdempotent() throws Exception {
        Deal deal = approvedDeal();
        String key = newKey();

        MvcResult first = disburse(deal.financierId(), deal.agreementId(), key);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("false");
        JsonNode disbursement = body(first);
        assertThat(disbursement.get("amount").decimalValue()).isEqualByComparingTo("480000.00");
        assertThat(disbursement.get("status").asText()).isEqualTo("SIMULATED_COMPLETED");
        assertThat(disbursement.get("reference").asText()).startsWith("SIM-");

        MvcResult replay = disburse(deal.financierId(), deal.agreementId(), key);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(replay).get("id").asText()).isEqualTo(disbursement.get("id").asText());

        MvcResult newKeySameAgreement = disburse(deal.financierId(), deal.agreementId(), newKey());
        assertThat(newKeySameAgreement.getResponse().getStatus()).isEqualTo(409);

        Deal other = approvedDeal();
        MvcResult reusedKey = disburse(other.financierId(), other.agreementId(), key);
        // Keys are scoped per caller, so another financier's identical key is a fresh request.
        assertThat(reusedKey.getResponse().getStatus()).isEqualTo(201);

        assertThat(disbursements.countByAgreementId(deal.agreementId())).isEqualTo(1);
    }

    @Test
    void sameKeyForADifferentRequestIsRejected() throws Exception {
        UUID financier = UUID.randomUUID();
        Deal first = approvedDealFor(financier);
        Deal second = approvedDealFor(financier);
        String key = newKey();
        assertThat(disburse(financier, first.agreementId(), key).getResponse().getStatus()).isEqualTo(201);

        MvcResult reused = disburse(financier, second.agreementId(), key);

        assertThat(reused.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(reused).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(disbursements.findByAgreementId(second.agreementId())).isEmpty();
    }

    @Test
    void idempotencyKeyIsRequiredAndValidated() throws Exception {
        Deal deal = approvedDeal();
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/disburse", deal.agreementId()), TestJwtSupport.financier(deal.financierId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/disburse", deal.agreementId()), TestJwtSupport.financier(deal.financierId()))
                        .header("Idempotency-Key", "bad key!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
    }

    @Test
    void disbursementFixesFeeAndBuildsSchedule() throws Exception {
        Deal deal = disbursedDeal();
        String seller = TestJwtSupport.seller(deal.sellerId());

        mockMvc.perform(withToken(get("/api/financing/agreements/{id}", deal.agreementId()), seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISBURSED"))
                .andExpect(jsonPath("$.feeAmount").value(FEE.doubleValue()))
                .andExpect(jsonPath("$.totalRepayable").value(TOTAL.doubleValue()))
                .andExpect(jsonPath("$.outstanding").value(TOTAL.doubleValue()))
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.nextDueDate").value(today().plusDays(30).toString()));

        JsonNode schedule = body(mockMvc.perform(withToken(get("/api/financing/agreements/{id}/schedule", deal.agreementId()), seller))
                .andExpect(status().isOk()).andReturn());
        assertThat(schedule).hasSize(2);
        assertThat(schedule.get(0).get("dueDate").asText()).isEqualTo(today().plusDays(30).toString());
        assertThat(schedule.get(1).get("dueDate").asText()).isEqualTo(today().plusDays(60).toString());
        assertThat(schedule.get(0).get("principalDue").decimalValue()).isEqualByComparingTo("240000.00");
        assertThat(schedule.get(0).get("feeDue").decimalValue()).isEqualByComparingTo("5234.24");
        assertThat(schedule.get(1).get("feeDue").decimalValue()).isEqualByComparingTo("5234.25");
        assertThat(schedule.get(0).get("status").asText()).isEqualTo("PENDING");

        mockMvc.perform(withToken(get("/api/financing/agreements/{id}/disbursement", deal.agreementId()), seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(480000.00));

        JsonNode history = body(mockMvc.perform(withToken(get("/api/financing/agreements/{id}/history", deal.agreementId()), seller))
                .andExpect(status().isOk()).andReturn());
        assertThat(history.findValuesAsText("toStatus")).containsExactly("PENDING_APPROVAL", "APPROVED", "DISBURSED");
    }

    @Test
    void partialRepaymentsAllocateFeeFirstAndFullRepaymentSettles() throws Exception {
        Deal deal = disbursedDeal();

        MvcResult partial = repay(deal.sellerId(), deal.agreementId(), "5000.00", newKey());
        assertThat(partial.getResponse().getStatus()).isEqualTo(201);
        JsonNode first = body(partial);
        assertThat(first.get("feeComponent").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(first.get("principalComponent").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(allocations.findByRepaymentId(UUID.fromString(first.get("id").asText()))).hasSize(1);

        JsonNode agreement = getAgreement(deal);
        assertThat(agreement.get("status").asText()).isEqualTo("REPAYMENT_IN_PROGRESS");
        assertThat(agreement.get("outstanding").decimalValue()).isEqualByComparingTo("485468.49");

        MvcResult tooMuch = repay(deal.sellerId(), deal.agreementId(), "485468.50", newKey());
        assertThat(tooMuch.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(tooMuch).get("code").asText()).isEqualTo("REPAYMENT_EXCEEDS_OUTSTANDING");

        MvcResult rest = repay(deal.sellerId(), deal.agreementId(), "485468.49", newKey());
        assertThat(rest.getResponse().getStatus()).isEqualTo(201);
        JsonNode last = body(rest);
        assertThat(last.get("feeComponent").decimalValue()).isEqualByComparingTo("5468.49");
        assertThat(last.get("principalComponent").decimalValue()).isEqualByComparingTo("480000.00");

        agreement = getAgreement(deal);
        assertThat(agreement.get("status").asText()).isEqualTo("SETTLED");
        assertThat(agreement.get("outstanding").decimalValue()).isEqualByComparingTo("0");
        assertThat(agreement.get("principalRepaid").decimalValue()).isEqualByComparingTo("480000.00");
        assertThat(agreement.get("feeRepaid").decimalValue()).isEqualByComparingTo(FEE);
        assertThat(agreement.get("settledAt").isNull()).isFalse();
        assertThat(agreement.get("nextDueDate").isNull()).isTrue();

        MvcResult afterSettlement = repay(deal.sellerId(), deal.agreementId(), "1.00", newKey());
        assertThat(afterSettlement.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(afterSettlement).get("code").asText()).isEqualTo("AGREEMENT_SETTLED");

        JsonNode list = body(mockMvc.perform(withToken(get("/api/financing/agreements/{id}/repayments", deal.agreementId()),
                TestJwtSupport.financier(deal.financierId()))).andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(2);
    }

    @Test
    void repaymentIsIdempotent() throws Exception {
        Deal deal = disbursedDeal();
        String key = newKey();

        MvcResult first = repay(deal.sellerId(), deal.agreementId(), "1000.00", key);
        MvcResult replay = repay(deal.sellerId(), deal.agreementId(), "1000.00", key);
        MvcResult changed = repay(deal.sellerId(), deal.agreementId(), "1000.01", key);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(replay).get("id").asText()).isEqualTo(body(first).get("id").asText());
        assertThat(changed.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(changed).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(getAgreement(deal).get("outstanding").decimalValue()).isEqualByComparingTo("489468.49");
    }

    @Test
    void repaymentsAreRecordedOnlyBySellerOfADisbursedAgreement() throws Exception {
        Deal pending = pendingDeal();
        MvcResult notDisbursed = repay(pending.sellerId(), pending.agreementId(), "10.00", newKey());
        assertThat(notDisbursed.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(notDisbursed).get("code").asText()).isEqualTo("REPAYMENT_NOT_ALLOWED");

        Deal deal = disbursedDeal();
        assertThat(repay(UUID.randomUUID(), deal.agreementId(), "10.00", newKey()).getResponse().getStatus()).isEqualTo(404);
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/repayments", deal.agreementId()), TestJwtSupport.financier(deal.financierId()))
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("amount", "10.00"))))
                .andExpect(status().isForbidden());
        assertThat(repay(deal.sellerId(), deal.agreementId(), "0.00", newKey()).getResponse().getStatus()).isEqualTo(400);
        assertThat(repay(deal.sellerId(), deal.agreementId(), "1.001", newKey()).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void overdueIsDerivedFromTheSchedule() throws Exception {
        Deal deal = disbursedDeal();
        clock.advance(Duration.ofDays(31));

        JsonNode agreement = getAgreement(deal);
        assertThat(agreement.get("overdue").asBoolean()).isTrue();
        assertThat(agreement.get("overdueAmount").decimalValue()).isEqualByComparingTo("245234.24");
        assertThat(agreement.get("status").asText()).isEqualTo("DISBURSED");

        JsonNode schedule = body(mockMvc.perform(withToken(get("/api/financing/agreements/{id}/schedule", deal.agreementId()),
                TestJwtSupport.seller(deal.sellerId()))).andExpect(status().isOk()).andReturn());
        assertThat(schedule.findValuesAsText("status")).containsExactly("OVERDUE", "PENDING");

        assertThat(repay(deal.sellerId(), deal.agreementId(), "245234.24", newKey()).getResponse().getStatus()).isEqualTo(201);
        assertThat(getAgreement(deal).get("overdue").asBoolean()).isFalse();
    }

    @Test
    void cancellationRulesAndReopening() throws Exception {
        Deal pending = pendingDeal();
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/cancel", pending.agreementId()), TestJwtSupport.financier(pending.financierId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_ALLOWED"));
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/cancel", pending.agreementId()), TestJwtSupport.seller(pending.sellerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // The invoice is open again; earlier offers stay closed, so a new offer is needed.
        mockMvc.perform(withToken(get("/api/financing/opportunities/{id}", pending.invoiceId()), TestJwtSupport.financier(UUID.randomUUID())))
                .andExpect(status().isOk());
        UUID newOffer = createOffer(pending.financierId(), pending.invoiceId());
        acceptOffer(pending.sellerId(), newOffer);

        Deal approved = approvedDeal();
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/cancel", approved.agreementId()), TestJwtSupport.seller(approved.sellerId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_ALLOWED"));
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/cancel", approved.agreementId()), TestJwtSupport.financier(approved.financierId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Deal disbursed = disbursedDeal();
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/cancel", disbursed.agreementId()), TestJwtSupport.financier(disbursed.financierId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void financierRejectionRecordsReason() throws Exception {
        Deal deal = pendingDeal();
        mockMvc.perform(withToken(post("/api/financing/agreements/{id}/reject", deal.agreementId()), TestJwtSupport.financier(deal.financierId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "Buyer concentration limit"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReason").value("Buyer concentration limit"));
    }

    @Test
    void agreementsAreVisibleOnlyToPartiesAndAdmins() throws Exception {
        Deal deal = pendingDeal();

        mockMvc.perform(withToken(get("/api/financing/agreements/{id}", deal.agreementId()), TestJwtSupport.seller(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/financing/agreements/{id}", deal.agreementId()), TestJwtSupport.financier(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/financing/agreements/{id}", deal.agreementId()), TestJwtSupport.admin(UUID.randomUUID())))
                .andExpect(status().isOk());

        JsonNode sellerList = body(mockMvc.perform(withToken(get("/api/financing/agreements"), TestJwtSupport.seller(deal.sellerId())))
                .andExpect(status().isOk()).andReturn());
        assertThat(sellerList.get("content").findValuesAsText("id")).containsExactly(deal.agreementId().toString());
        JsonNode strangerList = body(mockMvc.perform(withToken(get("/api/financing/agreements"), TestJwtSupport.financier(UUID.randomUUID())))
                .andExpect(status().isOk()).andReturn());
        assertThat(strangerList.get("totalElements").asLong()).isZero();
    }

    private Deal approvedDealFor(UUID financier) throws Exception {
        UUID seller = UUID.randomUUID();
        UUID invoice = listedInvoice(seller);
        UUID offer = createOffer(financier, invoice);
        UUID agreement = acceptOffer(seller, offer);
        approve(financier, agreement);
        return new Deal(seller, financier, invoice, offer, agreement);
    }

    private JsonNode getAgreement(Deal deal) throws Exception {
        return body(mockMvc.perform(withToken(get("/api/financing/agreements/{id}", deal.agreementId()), TestJwtSupport.seller(deal.sellerId())))
                .andExpect(status().isOk()).andReturn());
    }
}
