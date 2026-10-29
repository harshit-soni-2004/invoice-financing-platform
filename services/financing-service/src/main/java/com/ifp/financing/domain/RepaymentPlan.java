package com.ifp.financing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Schedule generation and repayment allocation. Rounding rules:
 * <ul>
 *   <li>Principal and fee are each split into {@code n} parts rounded DOWN to paise; the last installment takes the
 *       remainder, so the parts always add up exactly to the advance and the fee.</li>
 *   <li>Due dates are spread evenly from the disbursement date; the last one is always the invoice due date.</li>
 *   <li>A repayment is applied to the oldest installment first; within an installment accrued penalty interest is
 *       settled first, then the fee, then principal.</li>
 * </ul>
 */
public final class RepaymentPlan {

    public record Allocation(RepaymentInstallment installment, BigDecimal principalPart, BigDecimal feePart,
                             BigDecimal penaltyPart) {
    }

    private RepaymentPlan() {
    }

    public static List<RepaymentInstallment> schedule(UUID agreementId, LocalDate disbursementDate, LocalDate maturityDate,
                                                      int installments, BigDecimal advance, BigDecimal fee,
                                                      Supplier<UUID> ids) {
        long days = ChronoUnit.DAYS.between(disbursementDate, maturityDate);
        if (installments < 1) {
            throw new IllegalArgumentException("installments must be at least 1");
        }
        if (days < installments) {
            throw new BusinessRuleViolationException("MATURITY_TOO_SOON",
                    "Not enough days to maturity for " + installments + " installment(s)");
        }
        List<BigDecimal> principals = split(advance, installments);
        List<BigDecimal> fees = split(fee, installments);
        List<RepaymentInstallment> schedule = new ArrayList<>(installments);
        for (int k = 1; k <= installments; k++) {
            long offset = ceilDiv(days * k, installments);
            schedule.add(new RepaymentInstallment(ids.get(), agreementId, k, disbursementDate.plusDays(offset),
                    principals.get(k - 1), fees.get(k - 1)));
        }
        return schedule;
    }

    /**
     * Splits {@code amount} across installments (ordered by sequence). The caller must already have rejected amounts
     * larger than the total outstanding.
     */
    public static List<Allocation> allocate(List<RepaymentInstallment> installments, BigDecimal amount) {
        BigDecimal outstanding = installments.stream().map(RepaymentInstallment::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessRuleViolationException("REPAYMENT_EXCEEDS_OUTSTANDING",
                    "The repayment is larger than the amount outstanding");
        }
        List<Allocation> allocations = new ArrayList<>();
        BigDecimal left = amount;
        for (RepaymentInstallment installment : installments) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal penaltyPart = left.min(installment.penaltyRemaining());
            left = left.subtract(penaltyPart);
            BigDecimal feePart = left.min(installment.feeRemaining());
            left = left.subtract(feePart);
            BigDecimal principalPart = left.min(installment.principalRemaining());
            left = left.subtract(principalPart);
            if (penaltyPart.signum() > 0 || feePart.signum() > 0 || principalPart.signum() > 0) {
                installment.pay(principalPart, feePart, penaltyPart);
                allocations.add(new Allocation(installment, principalPart, feePart, penaltyPart));
            }
        }
        return allocations;
    }

    static List<BigDecimal> split(BigDecimal amount, int parts) {
        BigDecimal base = amount.divide(BigDecimal.valueOf(parts), 2, RoundingMode.DOWN);
        List<BigDecimal> result = new ArrayList<>(parts);
        for (int i = 1; i < parts; i++) {
            result.add(base);
        }
        result.add(amount.subtract(base.multiply(BigDecimal.valueOf(parts - 1))));
        return result;
    }

    private static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }
}
