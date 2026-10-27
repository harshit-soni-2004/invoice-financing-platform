package com.ifp.notification.service;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ifp.messaging.EventEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRulesTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID seller = UUID.randomUUID();
    private final UUID financier = UUID.randomUUID();

    private EventEnvelope event(String type, ObjectNode payload) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), "test", "AGREEMENT", "agg-1", null, null, payload);
    }

    private ObjectNode parties() {
        return mapper.createObjectNode().put("sellerId", seller.toString()).put("financierId", financier.toString())
                .put("advanceAmount", "480000.00").put("feeAmount", "9876.54");
    }

    @Test
    void disbursementNotifiesBothPartiesWithoutAmounts() {
        List<NotificationRules.Planned> planned = NotificationRules.plan(event("FinancingDisbursed", parties()));

        assertThat(planned).extracting(NotificationRules.Planned::recipientId).containsExactly(seller, financier);
        assertThat(planned).allSatisfy(p -> {
            assertThat(p.body()).doesNotContain("480000").doesNotContain("9876").contains("No real money was moved");
            assertThat(p.body()).contains("Reference: agreement agg-1");
        });
    }

    @Test
    void offerEventsGoToTheCounterparty() {
        assertThat(NotificationRules.plan(event("FinancingOfferCreated", parties())))
                .extracting(NotificationRules.Planned::recipientId).containsExactly(seller);
        assertThat(NotificationRules.plan(event("FinancingOfferAccepted", parties())))
                .extracting(NotificationRules.Planned::recipientId).containsExactly(financier);
        assertThat(NotificationRules.plan(event("FinancingOfferExpired", parties()))).isEmpty();
    }

    @Test
    void repaymentRemindersNameTheInstallmentAndDueDate() {
        ObjectNode payload = parties().put("sequenceNo", 2).put("dueDate", "2026-12-01");

        List<NotificationRules.Planned> dueSoon = NotificationRules.plan(event("FinancingRepaymentDueSoon", payload));
        List<NotificationRules.Planned> overdue = NotificationRules.plan(event("FinancingRepaymentOverdue", payload));

        assertThat(dueSoon).extracting(NotificationRules.Planned::recipientId).containsExactly(seller);
        assertThat(dueSoon.getFirst().body()).contains("Installment 2").contains("due on 2026-12-01");
        assertThat(overdue).extracting(NotificationRules.Planned::recipientId).containsExactly(seller, financier);
        assertThat(overdue).allSatisfy(p -> assertThat(p.body()).contains("not fully repaid").doesNotContain("480000"));
    }

    @Test
    void submissionIsConfirmedToTheSeller() {
        ObjectNode payload = mapper.createObjectNode().put("sellerId", seller.toString()).put("invoiceNumber", "INV-7")
                .put("amount", "125000.00");

        List<NotificationRules.Planned> planned = NotificationRules.plan(event("InvoiceSubmitted", payload));

        assertThat(planned).extracting(NotificationRules.Planned::recipientId).containsExactly(seller);
        assertThat(planned.getFirst().subject()).isEqualTo("Invoice INV-7 submitted");
        assertThat(planned.getFirst().body()).doesNotContain("125000");
    }

    @Test
    void invoiceNumbersCannotInjectMailHeaders() {
        ObjectNode payload = mapper.createObjectNode().put("sellerId", seller.toString())
                .put("invoiceNumber", "INV-1\r\nBcc: attacker@example.test");

        NotificationRules.Planned planned = NotificationRules.plan(event("InvoiceVerified", payload)).getFirst();

        assertThat(planned.subject()).doesNotContain("\r").doesNotContain("\n").startsWith("Invoice INV-1 Bcc:");
    }

    @Test
    void financierWelcomeMentionsApproval() {
        ObjectNode payload = mapper.createObjectNode().put("userId", financier.toString()).put("role", "FINANCIER")
                .put("email", "fin@example.test").put("displayName", "Fin");

        NotificationRules.Planned planned = NotificationRules.plan(event("UserRegistered", payload)).getFirst();

        assertThat(planned.recipientId()).isEqualTo(financier);
        assertThat(planned.body()).contains("administrator will review").doesNotContain("fin@example.test");
    }
}
