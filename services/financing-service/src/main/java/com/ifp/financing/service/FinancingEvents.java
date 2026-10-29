package com.ifp.financing.service;

import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.domain.FinancingAgreement;
import com.ifp.financing.domain.FinancingOffer;
import com.ifp.financing.domain.OfferStatus;
import com.ifp.financing.domain.Repayment;
import com.ifp.messaging.OutboxWriter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Financing events, appended to the outbox in the same transaction as the change they describe. Status reasons are
 * free text and are not published. A null actor means the system made the change (expiry, competing acceptance,
 * invoice withdrawal).
 */
@Component
public class FinancingEvents {

    public static final String OFFER = "OFFER";
    public static final String AGREEMENT = "AGREEMENT";

    private final OutboxWriter outbox;

    FinancingEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public record OfferChanged(UUID offerId, UUID invoiceId, UUID sellerId, UUID financierId, OfferStatus status,
                               OfferStatus previousStatus, BigDecimal advanceAmount, BigDecimal annualRate,
                               BigDecimal processingFee, int installmentCount, String currency, Instant expiresAt) {
    }

    /** {@code penaltyAccrued} and {@code penaltyRepaid} were added later; consumers must treat them as optional. */
    public record AgreementChanged(UUID agreementId, UUID offerId, UUID invoiceId, UUID sellerId, UUID financierId,
                                   AgreementStatus status, AgreementStatus previousStatus, BigDecimal advanceAmount,
                                   BigDecimal feeAmount, BigDecimal totalRepayable, BigDecimal principalRepaid,
                                   BigDecimal feeRepaid, String currency, LocalDate maturityDate,
                                   BigDecimal penaltyAccrued, BigDecimal penaltyRepaid) {
    }

    /** {@code amount = principalComponent + feeComponent + penaltyComponent}; the penalty field was added later. */
    public record RepaymentRecorded(UUID repaymentId, UUID agreementId, UUID invoiceId, UUID sellerId, UUID financierId,
                                    BigDecimal amount, BigDecimal principalComponent, BigDecimal feeComponent,
                                    String currency, AgreementStatus agreementStatus, BigDecimal penaltyComponent) {
    }

    /**
     * Penalty accrued on one agreement by one overdue sweep.
     *
     * @param accrualDate       business day of the sweep (the last day charged)
     * @param amount            penalty added by this sweep across all installments
     * @param penaltyAccrued    agreement total after this sweep
     * @param penaltyOutstanding agreement penalty still unpaid after this sweep
     */
    public record PenaltyAccrued(UUID agreementId, UUID invoiceId, UUID sellerId, UUID financierId, LocalDate accrualDate,
                                 BigDecimal dailyRate, BigDecimal amount, BigDecimal penaltyAccrued,
                                 BigDecimal penaltyRepaid, BigDecimal penaltyOutstanding, String currency,
                                 List<InstallmentPenalty> installments) {
    }

    /**
     * @param fromDate   first day charged by this sweep
     * @param toDate     last day charged by this sweep
     * @param days       days charged (fewer than the overdue days once the cap is reached)
     * @param baseAmount unpaid principal + fee the charge was computed on
     * @param capReached whether the installment's penalty is now at its cap (no further accrual)
     */
    public record InstallmentPenalty(UUID installmentId, int sequenceNo, LocalDate dueDate, LocalDate fromDate,
                                     LocalDate toDate, int days, BigDecimal baseAmount, BigDecimal amount,
                                     BigDecimal penaltyAccrued, boolean capReached) {
    }

    void offer(FinancingOffer offer, OfferStatus from, UUID actorId) {
        String action = offer.getStatus() == OfferStatus.OFFERED ? "created" : offer.getStatus().name().toLowerCase();
        outbox.append("financing.offer_" + action, "FinancingOffer" + pascal(action), OFFER, offer.getId(), actorId,
                new OfferChanged(offer.getId(), offer.getInvoiceId(), offer.getSellerId(), offer.getFinancierId(),
                        offer.getStatus(), from, offer.getAdvanceAmount(), offer.getAnnualRate(), offer.getProcessingFee(),
                        offer.getInstallmentCount(), offer.getCurrency(), offer.getExpiresAt()));
    }

    /** REPAYMENT_IN_PROGRESS is reported through {@link #repaymentRecorded}, not as a separate status event. */
    void agreement(FinancingAgreement agreement, AgreementStatus from, UUID actorId) {
        String action = switch (agreement.getStatus()) {
            case PENDING_APPROVAL -> "agreement_created";
            case REPAYMENT_IN_PROGRESS -> null;
            default -> agreement.getStatus().name().toLowerCase();
        };
        if (action == null) {
            return;
        }
        outbox.append("financing." + action, "Financing" + pascal(action), AGREEMENT, agreement.getId(), actorId,
                new AgreementChanged(agreement.getId(), agreement.getOfferId(), agreement.getInvoiceId(),
                        agreement.getSellerId(), agreement.getFinancierId(), agreement.getStatus(), from,
                        agreement.getAdvanceAmount(), agreement.getFeeAmount(), agreement.getTotalRepayable(),
                        agreement.getPrincipalRepaid(), agreement.getFeeRepaid(), agreement.getCurrency(),
                        agreement.getMaturityDate(), agreement.getPenaltyAccrued(), agreement.getPenaltyRepaid()));
    }

    void repaymentRecorded(FinancingAgreement agreement, Repayment repayment, UUID actorId) {
        outbox.append("financing.repayment_recorded", "FinancingRepaymentRecorded", AGREEMENT, agreement.getId(), actorId,
                new RepaymentRecorded(repayment.getId(), agreement.getId(), agreement.getInvoiceId(), agreement.getSellerId(),
                        agreement.getFinancierId(), repayment.getAmount(), repayment.getPrincipalComponent(),
                        repayment.getFeeComponent(), repayment.getCurrency(), agreement.getStatus(),
                        repayment.getPenaltyComponent()));
    }

    /** System event (no actor), published only when the sweep actually charged something. */
    void penaltyAccrued(FinancingAgreement agreement, LocalDate accrualDate, BigDecimal dailyRate, BigDecimal amount,
                        List<InstallmentPenalty> installments) {
        outbox.append("financing.penalty_accrued", "FinancingPenaltyAccrued", AGREEMENT, agreement.getId(), null,
                new PenaltyAccrued(agreement.getId(), agreement.getInvoiceId(), agreement.getSellerId(),
                        agreement.getFinancierId(), accrualDate, dailyRate, amount, agreement.getPenaltyAccrued(),
                        agreement.getPenaltyRepaid(), agreement.penaltyOutstanding(), agreement.getCurrency(),
                        List.copyOf(installments)));
    }

    public record RepaymentReminder(UUID agreementId, UUID installmentId, UUID invoiceId, UUID sellerId, UUID financierId,
                                    int sequenceNo, LocalDate dueDate) {
    }

    void repaymentReminder(RepaymentReminderService.DueInstallment installment, RepaymentReminderService.Kind kind) {
        String action = "repayment_" + kind.name().toLowerCase();
        outbox.append("financing." + action, "Financing" + pascal(action), AGREEMENT, installment.agreementId(), null,
                new RepaymentReminder(installment.agreementId(), installment.installmentId(), installment.invoiceId(),
                        installment.sellerId(), installment.financierId(), installment.sequenceNo(), installment.dueDate()));
    }

    static String pascal(String snake) {
        StringBuilder out = new StringBuilder();
        for (String part : snake.split("_")) {
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
