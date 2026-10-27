package com.ifp.invoice.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T05:00:00Z");
    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    static InvoiceDetails details(String number) {
        return new InvoiceDetails(number, "  Acme Buyers Pvt Ltd ", "27AAPFU0939F1ZV", " Steel coils ",
                new BigDecimal("500000.00"), LocalDate.parse("2026-09-30"), LocalDate.parse("2026-11-29"));
    }

    private Invoice draft() {
        return Invoice.createDraft(UUID.randomUUID(), SELLER, details("INV-001"), T0);
    }

    @Test
    void createsDraftInInrWithNormalisedText() {
        Invoice invoice = draft();

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(invoice.getCurrency()).isEqualTo("INR");
        assertThat(invoice.getBuyerName()).isEqualTo("Acme Buyers Pvt Ltd");
        assertThat(invoice.getDescription()).isEqualTo("Steel coils");
        assertThat(invoice.getAmount()).isEqualByComparingTo("500000.00");
        assertThat(invoice.getCreatedAt()).isEqualTo(T0);
        assertThat(invoice.getUpdatedAt()).isEqualTo(T0);
        assertThat(invoice.isOwnedBy(SELLER)).isTrue();
        assertThat(invoice.isOwnedBy(UUID.randomUUID())).isFalse();
    }

    @Test
    void walksHappyPathAndRecordsWhoVerified() {
        Invoice invoice = draft();
        Instant t1 = T0.plusSeconds(60);

        invoice.submit(t1);
        invoice.confirmBuyer(ADMIN, t1.plusSeconds(1));
        invoice.verify(ADMIN, t1.plusSeconds(2));
        invoice.listForFinancing(t1.plusSeconds(3));
        invoice.markFinanced(t1.plusSeconds(4));
        invoice.markSettled(t1.plusSeconds(5));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.SETTLED);
        assertThat(invoice.getSubmittedAt()).isEqualTo(t1);
        assertThat(invoice.getBuyerConfirmedBy()).isEqualTo(ADMIN);
        assertThat(invoice.getVerifiedBy()).isEqualTo(ADMIN);
        assertThat(invoice.getFinancedAt()).isEqualTo(t1.plusSeconds(4));
        assertThat(invoice.getSettledAt()).isEqualTo(t1.plusSeconds(5));
        assertThat(invoice.getUpdatedAt()).isEqualTo(t1.plusSeconds(5));
    }

    @Test
    void cannotSkipBuyerConfirmation() {
        Invoice invoice = draft();
        invoice.submit(T0);

        assertThatThrownBy(() -> invoice.verify(ADMIN, T0))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessage("Invoice cannot move from SUBMITTED to VERIFIED");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.SUBMITTED);
    }

    @Test
    void cannotListForFinancingBeforeVerification() {
        Invoice invoice = draft();

        assertThatThrownBy(() -> invoice.listForFinancing(T0)).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void rejectRequiresReasonAndStoresIt() {
        Invoice invoice = draft();
        invoice.submit(T0);

        assertThatThrownBy(() -> invoice.reject("  ", T0))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("REASON_REQUIRED");

        invoice.reject(" Buyer denies the debt ", T0);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(invoice.getStatusReason()).isEqualTo("Buyer denies the debt");
    }

    @Test
    void cancelledInvoiceIsTerminal() {
        Invoice invoice = draft();
        invoice.cancel(null, T0);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThatThrownBy(() -> invoice.submit(T0)).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void onlyDraftCanBeEdited() {
        Invoice invoice = draft();
        invoice.updateDetails(details("INV-002"), T0.plusSeconds(5));
        assertThat(invoice.getInvoiceNumber()).isEqualTo("INV-002");

        invoice.submit(T0.plusSeconds(10));
        assertThatThrownBy(() -> invoice.updateDetails(details("INV-003"), T0))
                .isInstanceOf(InvoiceNotEditableException.class);
        assertThat(invoice.getInvoiceNumber()).isEqualTo("INV-002");
    }

    @Test
    void financedCannotBeCancelled() {
        Invoice invoice = draft();
        invoice.submit(T0);
        invoice.confirmBuyer(ADMIN, T0);
        invoice.verify(ADMIN, T0);
        invoice.listForFinancing(T0);
        invoice.markFinanced(T0);

        assertThatThrownBy(() -> invoice.cancel("changed my mind", T0)).isInstanceOf(InvalidStatusTransitionException.class);
    }
}
