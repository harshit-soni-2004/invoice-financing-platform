package com.ifp.financing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Penalty interest on an overdue installment. Rules:
 * <ul>
 *   <li>Each calendar day (business zone) after the due date on which the installment still has unpaid principal or
 *       fee is charged {@code unpaid principal + fee x dailyRate}, rounded HALF_EVEN to paise. Penalty is simple:
 *       it is never charged on unpaid penalty.</li>
 *   <li>Missed days (a sweep did not run) are caught up on the next run, each on the unpaid amount at that run;
 *       repayments only lower the base, so a catch-up never overcharges.</li>
 *   <li>The total penalty of an installment never exceeds {@code (principalDue + feeDue) x capRatio}, rounded DOWN;
 *       the day that reaches the cap is charged only the remainder.</li>
 * </ul>
 *
 * @param dailyRate fraction per day (0.0005 = 0.05% per day)
 * @param capRatio  cap as a fraction of the installment's scheduled amount
 */
public record PenaltyPolicy(BigDecimal dailyRate, BigDecimal capRatio) {

    public record DayCharge(LocalDate date, BigDecimal amount) {
    }

    /**
     * @param through   last day examined (the installment's accrued-through date afterwards)
     * @param base      unpaid principal + fee the charges were computed on
     * @param charges   one entry per day actually charged (never zero amounts)
     * @param capReached whether the installment's penalty is now at the cap
     */
    public record Accrual(LocalDate through, BigDecimal base, List<DayCharge> charges, boolean capReached) {
        public BigDecimal total() {
            return charges.stream().map(DayCharge::amount).reduce(Money.ZERO, BigDecimal::add);
        }

        public LocalDate from() {
            return charges.isEmpty() ? null : charges.getFirst().date();
        }
    }

    public PenaltyPolicy {
        if (dailyRate.signum() < 0 || capRatio.signum() < 0) {
            throw new IllegalArgumentException("Penalty rate and cap must not be negative");
        }
    }

    public BigDecimal cap(RepaymentInstallment installment) {
        return installment.totalDue().multiply(capRatio).setScale(2, RoundingMode.DOWN);
    }

    public BigDecimal dailyCharge(BigDecimal base) {
        return base.multiply(dailyRate).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Charges for the days from the installment's first unaccrued day through {@code today}, or null when there is
     * nothing to examine (not overdue, fully paid, or already accrued through today).
     */
    public Accrual accrue(RepaymentInstallment installment, LocalDate today) {
        BigDecimal base = installment.scheduledRemaining();
        LocalDate first = installment.firstUnaccruedDay();
        if (base.signum() == 0 || !installment.getDueDate().isBefore(today) || first.isAfter(today)) {
            return null;
        }
        BigDecimal capLeft = cap(installment).subtract(installment.getPenaltyAccrued()).max(Money.ZERO);
        BigDecimal daily = dailyCharge(base);
        List<DayCharge> charges = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(today) && capLeft.signum() > 0 && daily.signum() > 0; day = day.plusDays(1)) {
            BigDecimal amount = daily.min(capLeft);
            charges.add(new DayCharge(day, amount));
            capLeft = capLeft.subtract(amount);
        }
        return new Accrual(today, base, List.copyOf(charges), capLeft.signum() == 0);
    }
}
