package com.ifp.financing.web.dto;

import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.domain.Disbursement;
import com.ifp.financing.domain.FinanceableInvoice;
import com.ifp.financing.domain.FinancingAgreement;
import com.ifp.financing.domain.FinancingOffer;
import com.ifp.financing.domain.OfferStatus;
import com.ifp.financing.domain.Repayment;
import com.ifp.financing.domain.RepaymentInstallment;
import com.ifp.financing.domain.StatusHistoryEntry;
import com.ifp.financing.service.AgreementService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Request and response bodies. Owner and party IDs never come from requests; they come from the access token. */
public final class FinancingDtos {

    private FinancingDtos() {
    }

    // ---- Requests ----

    public record CreateOfferRequest(
            @NotNull UUID invoiceId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal advanceAmount,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 1, fraction = 4) BigDecimal annualRate,
            @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal processingFee,
            @NotNull @Min(1) @Max(60) Integer installmentCount,
            @NotNull Instant expiresAt) {
    }

    public record ReasonRequest(@Size(max = 500) String reason) {
    }

    public record RepaymentRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    // ---- Responses ----

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
            return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }

    public record OpportunityResponse(UUID invoiceId, String invoiceNumber, String buyerName, BigDecimal amount,
                                      String currency, LocalDate issueDate, LocalDate dueDate, long daysToMaturity,
                                      BigDecimal maxAdvanceAmount) {
        public static OpportunityResponse from(FinanceableInvoice invoice, LocalDate today, BigDecimal maxAdvance) {
            return new OpportunityResponse(invoice.getInvoiceId(), invoice.getInvoiceNumber(), invoice.getBuyerName(),
                    invoice.getAmount(), invoice.getCurrency(), invoice.getIssueDate(), invoice.getDueDate(),
                    ChronoUnit.DAYS.between(today, invoice.getDueDate()), maxAdvance);
        }
    }

    public record OfferResponse(UUID id, UUID invoiceId, UUID sellerId, UUID financierId, BigDecimal advanceAmount,
                                BigDecimal annualRate, BigDecimal processingFee, int installmentCount, String currency,
                                Instant expiresAt, OfferStatus status, String statusReason, Instant decidedAt,
                                Instant createdAt) {
        public static OfferResponse from(FinancingOffer offer) {
            return new OfferResponse(offer.getId(), offer.getInvoiceId(), offer.getSellerId(), offer.getFinancierId(),
                    offer.getAdvanceAmount(), offer.getAnnualRate(), offer.getProcessingFee(), offer.getInstallmentCount(),
                    offer.getCurrency(), offer.getExpiresAt(), offer.getStatus(), offer.getStatusReason(),
                    offer.getDecidedAt(), offer.getCreatedAt());
        }
    }

    public record AgreementResponse(UUID id, UUID offerId, UUID invoiceId, UUID sellerId, UUID financierId,
                                    BigDecimal invoiceAmount, BigDecimal advanceAmount, BigDecimal annualRate,
                                    BigDecimal processingFee, int installmentCount, String currency,
                                    LocalDate maturityDate, AgreementStatus status, String statusReason,
                                    BigDecimal feeAmount, BigDecimal totalRepayable, BigDecimal principalRepaid,
                                    BigDecimal feeRepaid, BigDecimal outstanding, BigDecimal principalOutstanding,
                                    BigDecimal feeOutstanding, Boolean overdue, BigDecimal overdueAmount,
                                    LocalDate nextDueDate, Instant approvedAt, Instant disbursedAt, Instant settledAt,
                                    Instant createdAt, BigDecimal penaltyAccrued, BigDecimal penaltyRepaid,
                                    BigDecimal penaltyOutstanding) {

        public static AgreementResponse from(AgreementService.AgreementDetails details) {
            return of(details.agreement(), details.overdueAmount(), details.nextDueDate());
        }

        /** Summary form (lists, state changes): overdue figures need the schedule, so they are omitted. */
        public static AgreementResponse from(FinancingAgreement agreement) {
            return of(agreement, null, null);
        }

        private static AgreementResponse of(FinancingAgreement a, BigDecimal overdueAmount, LocalDate nextDueDate) {
            return new AgreementResponse(a.getId(), a.getOfferId(), a.getInvoiceId(), a.getSellerId(), a.getFinancierId(),
                    a.getInvoiceAmount(), a.getAdvanceAmount(), a.getAnnualRate(), a.getProcessingFee(),
                    a.getInstallmentCount(), a.getCurrency(), a.getMaturityDate(), a.getStatus(), a.getStatusReason(),
                    a.getFeeAmount(), a.getTotalRepayable(), a.getPrincipalRepaid(), a.getFeeRepaid(), a.outstanding(),
                    a.principalOutstanding(), a.feeOutstanding(),
                    overdueAmount == null ? null : overdueAmount.signum() > 0, overdueAmount, nextDueDate,
                    a.getApprovedAt(), a.getDisbursedAt(), a.getSettledAt(), a.getCreatedAt(),
                    a.getPenaltyAccrued(), a.getPenaltyRepaid(), a.penaltyOutstanding());
        }
    }

    public record DisbursementResponse(UUID id, UUID agreementId, BigDecimal amount, String currency, String status,
                                       String reference, Instant disbursedAt) {
        public static DisbursementResponse from(Disbursement d) {
            return new DisbursementResponse(d.getId(), d.getAgreementId(), d.getAmount(), d.getCurrency(), d.getStatus(),
                    d.getReference(), d.getDisbursedAt());
        }
    }

    /**
     * {@code totalDue} is the scheduled principal + fee; {@code remaining} is everything still owed on the installment,
     * including unpaid penalty ({@code penaltyRemaining}), so it is zero exactly when the status is PAID.
     */
    public record InstallmentResponse(int sequenceNo, LocalDate dueDate, BigDecimal principalDue, BigDecimal feeDue,
                                      BigDecimal totalDue, BigDecimal principalPaid, BigDecimal feePaid,
                                      BigDecimal remaining, RepaymentInstallment.Status status,
                                      BigDecimal penaltyAccrued, BigDecimal penaltyPaid, BigDecimal penaltyRemaining,
                                      LocalDate penaltyAccruedThrough) {
        public static InstallmentResponse from(RepaymentInstallment i, LocalDate today) {
            return new InstallmentResponse(i.getSequenceNo(), i.getDueDate(), i.getPrincipalDue(), i.getFeeDue(),
                    i.totalDue(), i.getPrincipalPaid(), i.getFeePaid(), i.remaining(), i.statusOn(today),
                    i.getPenaltyAccrued(), i.getPenaltyPaid(), i.penaltyRemaining(), i.getPenaltyAccruedThrough());
        }
    }

    public record RepaymentResponse(UUID id, UUID agreementId, BigDecimal amount, BigDecimal principalComponent,
                                    BigDecimal feeComponent, String currency, Instant receivedAt,
                                    BigDecimal penaltyComponent) {
        public static RepaymentResponse from(Repayment r) {
            return new RepaymentResponse(r.getId(), r.getAgreementId(), r.getAmount(), r.getPrincipalComponent(),
                    r.getFeeComponent(), r.getCurrency(), r.getReceivedAt(), r.getPenaltyComponent());
        }
    }

    /** Actor IDs are only shown to admins; parties see the role that acted. */
    public record HistoryEntryResponse(String fromStatus, String toStatus, String actorRole, UUID actorId,
                                       String reason, Instant occurredAt) {
        public static HistoryEntryResponse from(StatusHistoryEntry e, boolean includeActorId) {
            return new HistoryEntryResponse(e.getFromStatus(), e.getToStatus(), e.getActorRole(),
                    includeActorId ? e.getActorId() : null, e.getReason(), e.getOccurredAt());
        }
    }
}
