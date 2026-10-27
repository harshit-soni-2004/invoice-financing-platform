package com.ifp.financing.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void financingFeeMatchesDocumentedExample() {
        // 480000 x 0.12 x 60 / 365 = 9468.4931... -> 9468.49
        assertThat(Money.financingFee(new BigDecimal("480000.00"), new BigDecimal("0.1200"), 60, Money.ZERO))
                .isEqualByComparingTo("9468.49");
    }

    @Test
    void financingFeeAddsProcessingFee() {
        assertThat(Money.financingFee(new BigDecimal("480000.00"), new BigDecimal("0.1200"), 60, new BigDecimal("1000.00")))
                .isEqualByComparingTo("10468.49");
    }

    @Test
    void financingFeeRoundsHalfEven() {
        // 1.00 x 0.0365 x days / 365 = days / 10000: exact ties at the third decimal go to the even neighbour
        assertThat(Money.financingFee(new BigDecimal("1.00"), new BigDecimal("0.0365"), 50, Money.ZERO))
                .isEqualByComparingTo("0.00"); // 0.005 -> 0.00
        assertThat(Money.financingFee(new BigDecimal("1.00"), new BigDecimal("0.0365"), 150, Money.ZERO))
                .isEqualByComparingTo("0.02"); // 0.015 -> 0.02
    }

    @Test
    void zeroDaysMeansOnlyProcessingFee() {
        assertThat(Money.financingFee(new BigDecimal("1000.00"), new BigDecimal("0.2000"), 0, new BigDecimal("5.00")))
                .isEqualByComparingTo("5.00");
    }

    @Test
    void exactRejectsSubPaise() {
        assertThatThrownBy(() -> Money.exact(new BigDecimal("10.005"), "amount"))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(Money.exact(new BigDecimal("10.5"), "amount")).isEqualTo(new BigDecimal("10.50"));
        assertThat(Money.exact(new BigDecimal("10.500"), "amount")).isEqualTo(new BigDecimal("10.50"));
    }

    @Test
    void exactRejectsNull() {
        assertThatThrownBy(() -> Money.exact(null, "amount")).isInstanceOf(BusinessRuleViolationException.class);
    }
}
