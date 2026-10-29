package com.ifp.financing.config;

import com.ifp.financing.domain.PenaltyPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;

/**
 * Bounds on what a financing offer may contain. The actual terms (rate, processing fee, installments) are always
 * chosen explicitly by the financier per offer; nothing here is a default interest rate.
 *
 * @param businessZone             zone that defines "today" for date rules (timestamps are stored in UTC)
 * @param maxAdvanceRatio          largest advance as a fraction of the invoice amount
 * @param maxAnnualRate            largest annual discount rate an offer may carry (fraction, 0.36 = 36% p.a.)
 * @param maxOfferValidity         longest time an offer may stay open
 * @param minDaysToMaturity        an invoice must be at least this many days from its due date to be financed
 * @param maxInstallments          largest number of repayment installments an offer may specify
 * @param offerExpirySweepInterval how often expired offers are marked EXPIRED
 * @param repaymentReminderDays    an unpaid installment due within this many days gets a "due soon" reminder
 * @param penalty                  penalty interest on overdue installments, accrued by the overdue sweep
 */
@Validated
@ConfigurationProperties(prefix = "financing")
public record FinancingProperties(
        @NotNull ZoneId businessZone,
        @NotNull @DecimalMin("0.01") @DecimalMax("1.00") BigDecimal maxAdvanceRatio,
        @NotNull @DecimalMin("0.0001") @DecimalMax("0.9999") BigDecimal maxAnnualRate,
        @NotNull Duration maxOfferValidity,
        @PositiveOrZero int minDaysToMaturity,
        @Min(1) @Max(60) int maxInstallments,
        @NotNull Duration offerExpirySweepInterval,
        @Min(1) @Max(30) int repaymentReminderDays,
        @NotNull @Valid Penalty penalty) {

    /**
     * Platform-wide penalty terms (not part of an offer). Changing them affects only days accrued afterwards; days
     * already accrued keep the rate they were charged at ({@code penalty_accruals.daily_rate}).
     *
     * @param enabled   whether the overdue sweep accrues penalty at all
     * @param dailyRate fraction of the unpaid principal + fee charged per overdue day (0.0005 = 0.05% per day)
     * @param capRatio  total penalty per installment is capped at this fraction of its scheduled amount
     */
    public record Penalty(
            boolean enabled,
            @NotNull @DecimalMin("0.000001") @DecimalMax("0.010000") @Digits(integer = 1, fraction = 6) BigDecimal dailyRate,
            @NotNull @DecimalMin("0.00") @DecimalMax("1.00") BigDecimal capRatio) {

        public PenaltyPolicy policy() {
            return new PenaltyPolicy(dailyRate, capRatio);
        }
    }
}
