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

    public record AgreementChanged(UUID agreementId, UUID offerId, UUID invoiceId, UUID sellerId, UUID financierId,
                                   AgreementStatus status, AgreementStatus previousStatus, BigDecimal advanceAmount,
                                   BigDecimal feeAmount, BigDecimal totalRepayable, BigDecimal principalRepaid,
                                   BigDecimal feeRepaid, String currency, LocalDate maturityDate) {
    }

    public record RepaymentRecorded(UUID repaymentId, UUID agreementId, UUID invoiceId, UUID sellerId, UUID financierId,
                                    BigDecimal amount, BigDecimal principalComponent, BigDecimal feeComponent,
                                    String currency, AgreementStatus agreementStatus) {
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
                        agreement.getMaturityDate()));
    }

    void repaymentRecorded(FinancingAgreement agreement, Repayment repayment, UUID actorId) {
        outbox.append("financing.repayment_recorded", "FinancingRepaymentRecorded", AGREEMENT, agreement.getId(), actorId,
                new RepaymentRecorded(repayment.getId(), agreement.getId(), agreement.getInvoiceId(), agreement.getSellerId(),
                        agreement.getFinancierId(), repayment.getAmount(), repayment.getPrincipalComponent(),
                        repayment.getFeeComponent(), repayment.getCurrency(), agreement.getStatus()));
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
