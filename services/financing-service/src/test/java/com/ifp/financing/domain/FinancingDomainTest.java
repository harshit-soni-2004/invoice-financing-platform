package com.ifp.financing.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinancingDomainTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 1);
    private final UUID invoiceId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();

    @Test
    void projectionIgnoresDuplicateAndOlderSnapshots() {
        FinanceableInvoice invoice = FinanceableInvoice.from(snapshot(InvoiceState.SUBMITTED, 2), NOW);

        assertThat(invoice.apply(snapshot(InvoiceState.FINANCING_AVAILABLE, 2), NOW)).isFalse();
        assertThat(invoice.apply(snapshot(InvoiceState.DRAFT, 1), NOW)).isFalse();
        assertThat(invoice.getInvoiceStatus()).isEqualTo(InvoiceState.SUBMITTED);

        assertThat(invoice.apply(snapshot(InvoiceState.FINANCING_AVAILABLE, 4), NOW)).isTrue();
        assertThat(invoice.getInvoiceStatus()).isEqualTo(InvoiceState.FINANCING_AVAILABLE);
        assertThat(invoice.getSourceVersion()).isEqualTo(4);
    }

    @Test
    void projectionRejectsSellerChange() {
        FinanceableInvoice invoice = FinanceableInvoice.from(snapshot(InvoiceState.SUBMITTED, 1), NOW);
        InvoiceSnapshot hijack = new InvoiceSnapshot(invoiceId, UUID.randomUUID(), "INV-1", "Buyer", new BigDecimal("100.00"),
                "INR", TODAY.minusDays(1), TODAY.plusDays(30), InvoiceState.FINANCING_AVAILABLE, 2, NOW);

        assertThatThrownBy(() -> invoice.apply(hijack, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(invoice.getSellerId()).isEqualTo(sellerId);
    }

    @Test
    void snapshotRejectsForeignCurrencyAndBadAmounts() {
        assertThatThrownBy(() -> new InvoiceSnapshot(invoiceId, sellerId, "INV-1", "Buyer", new BigDecimal("100.00"),
                "USD", TODAY, TODAY.plusDays(1), InvoiceState.DRAFT, 0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvoiceSnapshot(invoiceId, sellerId, "INV-1", "Buyer", BigDecimal.ZERO,
                "INR", TODAY, TODAY.plusDays(1), InvoiceState.DRAFT, 0, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maturityWindowIsInclusive() {
        FinanceableInvoice invoice = FinanceableInvoice.from(snapshot(InvoiceState.FINANCING_AVAILABLE, 1), NOW);
        assertThat(invoice.isWithinMaturityWindow(TODAY.plusDays(23), 7)).isTrue();  // due = today + 30
        assertThat(invoice.isWithinMaturityWindow(TODAY.plusDays(24), 7)).isFalse();
    }

    @Test
    void offerExpiresAtItsExpiryInstant() {
        FinancingOffer offer = offer(NOW.plusSeconds(60));
        assertThat(offer.isExpiredAt(NOW.plusSeconds(59))).isFalse();
        assertThat(offer.isExpiredAt(NOW.plusSeconds(60))).isTrue();
    }

    @Test
    void terminalOfferCannotBeAcceptedAgain() {
        FinancingOffer offer = offer(NOW.plusSeconds(60));
        offer.reject("no", NOW);
        assertThatThrownBy(() -> offer.accept(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void agreementSettlesWhenFullyRepaid() {
        FinancingAgreement agreement = disbursedAgreement();
        assertThat(agreement.outstanding()).isEqualByComparingTo("10100.00");

        agreement.applyRepayment(new BigDecimal("400.00"), new BigDecimal("100.00"), NOW);
        assertThat(agreement.getStatus()).isEqualTo(AgreementStatus.REPAYMENT_IN_PROGRESS);
        assertThat(agreement.outstanding()).isEqualByComparingTo("9600.00");

        agreement.applyRepayment(new BigDecimal("9600.00"), Money.ZERO, NOW);
        assertThat(agreement.getStatus()).isEqualTo(AgreementStatus.SETTLED);
        assertThat(agreement.getSettledAt()).isEqualTo(NOW);
        assertThat(agreement.outstanding()).isEqualByComparingTo("0");
    }

    @Test
    void settledAgreementRefusesRepayments() {
        FinancingAgreement agreement = disbursedAgreement();
        agreement.applyRepayment(new BigDecimal("10000.00"), new BigDecimal("100.00"), NOW);

        assertThatThrownBy(() -> agreement.applyRepayment(new BigDecimal("1.00"), Money.ZERO, NOW))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void agreementCannotBeDisbursedBeforeApproval() {
        FinancingAgreement agreement = pendingAgreement();
        assertThatThrownBy(() -> agreement.markDisbursed(new BigDecimal("100.00"), NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThat(agreement.getFeeAmount()).isNull();
    }

    @Test
    void disbursementPaysTheFullAdvance() {
        FinancingAgreement agreement = disbursedAgreement();
        Disbursement disbursement = Disbursement.simulated(UUID.randomUUID(), agreement, UUID.randomUUID(), NOW);
        assertThat(disbursement.getAmount()).isEqualByComparingTo("10000.00");
        assertThat(disbursement.getReference()).matches("SIM-[0-9A-F]{16}");
    }

    private FinancingAgreement disbursedAgreement() {
        FinancingAgreement agreement = pendingAgreement();
        agreement.approve(NOW);
        agreement.markDisbursed(new BigDecimal("100.00"), NOW);
        return agreement;
    }

    private FinancingAgreement pendingAgreement() {
        FinancingOffer offer = offer(NOW.plusSeconds(60));
        offer.accept(NOW);
        return FinancingAgreement.fromAcceptedOffer(UUID.randomUUID(), offer,
                FinanceableInvoice.from(snapshot(InvoiceState.FINANCING_AVAILABLE, 1), NOW), NOW);
    }

    private FinancingOffer offer(Instant expiresAt) {
        FinanceableInvoice invoice = FinanceableInvoice.from(snapshot(InvoiceState.FINANCING_AVAILABLE, 1), NOW);
        return FinancingOffer.create(UUID.randomUUID(), invoice, UUID.randomUUID(),
                new OfferTerms(new BigDecimal("10000.00"), new BigDecimal("0.1200"), Money.ZERO, 1), expiresAt, NOW);
    }

    private InvoiceSnapshot snapshot(InvoiceState status, long version) {
        return new InvoiceSnapshot(invoiceId, sellerId, "INV-1", "Buyer", new BigDecimal("20000.00"), "INR",
                TODAY.minusDays(1), TODAY.plusDays(30), status, version, NOW);
    }
}
