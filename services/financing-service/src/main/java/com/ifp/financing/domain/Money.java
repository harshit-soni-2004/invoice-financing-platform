package com.ifp.financing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** INR amounts: BigDecimal with exactly two decimal places. Never double or float. */
public final class Money {

    public static final String CURRENCY = "INR";
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

    private Money() {
    }

    /** Rejects amounts with more than two decimals instead of silently rounding them. */
    public static BigDecimal exact(BigDecimal amount, String field) {
        if (amount == null) {
            throw new BusinessRuleViolationException("AMOUNT_REQUIRED", field + " is required");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessRuleViolationException("AMOUNT_PRECISION", field + " may have at most 2 decimal places");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Discount fee: {@code advance x annualRate x days / 365}, rounded HALF_EVEN to paise, plus the flat processing fee.
     * Example: 4,80,000.00 at 12% for 60 days = 9,468.49 (+ processing fee).
     */
    public static BigDecimal financingFee(BigDecimal advance, BigDecimal annualRate, long days, BigDecimal processingFee) {
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative");
        }
        BigDecimal discount = advance.multiply(annualRate).multiply(BigDecimal.valueOf(days))
                .divide(DAYS_PER_YEAR, 2, RoundingMode.HALF_EVEN);
        return discount.add(processingFee).setScale(2, RoundingMode.UNNECESSARY);
    }

    public static boolean isPositive(BigDecimal amount) {
        return amount.signum() > 0;
    }
}
