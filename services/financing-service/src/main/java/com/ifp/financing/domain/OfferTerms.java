package com.ifp.financing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Commercial terms chosen by the financier.
 *
 * @param advanceAmount    amount disbursed to the seller (INR)
 * @param annualRate       annual discount rate as a fraction (0.1200 = 12% p.a.), at most 4 decimals
 * @param processingFee    flat fee repaid with the advance (not deducted from the disbursement)
 * @param installmentCount number of repayment installments ending on the invoice due date
 */
public record OfferTerms(BigDecimal advanceAmount, BigDecimal annualRate, BigDecimal processingFee, int installmentCount) {

    public OfferTerms {
        advanceAmount = Money.exact(advanceAmount, "advanceAmount");
        processingFee = Money.exact(processingFee == null ? BigDecimal.ZERO : processingFee, "processingFee");
        Objects.requireNonNull(annualRate, "annualRate");
        if (annualRate.stripTrailingZeros().scale() > 4) {
            throw new BusinessRuleViolationException("RATE_PRECISION", "annualRate may have at most 4 decimal places");
        }
        annualRate = annualRate.setScale(4, RoundingMode.UNNECESSARY);
    }
}
