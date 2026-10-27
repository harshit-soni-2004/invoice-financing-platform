package com.ifp.financing.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
        @Min(1) @Max(30) int repaymentReminderDays) {
}
