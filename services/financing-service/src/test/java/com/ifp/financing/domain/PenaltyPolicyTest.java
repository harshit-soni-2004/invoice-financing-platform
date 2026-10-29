package com.ifp.financing.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PenaltyPolicyTest {

    private static final LocalDate DUE = LocalDate.of(2026, 3, 31);
    private static final PenaltyPolicy POLICY = new PenaltyPolicy(new BigDecimal("0.000500"), new BigDecimal("0.10"));

    @Test
    void nothingAccruesOnOrBeforeTheDueDateOrOnceThePrincipalAndFeeArePaid() {
        RepaymentInstallment installment = installment("10000.00", "500.00");
        assertThat(POLICY.accrue(installment, DUE)).isNull();
        assertThat(POLICY.accrue(installment, DUE.minusDays(3))).isNull();

        installment.pay(new BigDecimal("10000.00"), new BigDecimal("500.00"));
        assertThat(POLICY.accrue(installment, DUE.plusDays(10))).isNull();
    }

    @Test
    void eachOverdueDayIsChargedOnTheUnpaidPrincipalAndFeeAndMissedDaysAreCaughtUp() {
        RepaymentInstallment installment = installment("10000.00", "500.00");

        PenaltyPolicy.Accrual accrual = POLICY.accrue(installment, DUE.plusDays(3));

        // 10,500.00 x 0.0005 = 5.25 per day, for the 1st, 2nd and 3rd day after the due date.
        assertThat(accrual.charges()).extracting(PenaltyPolicy.DayCharge::date)
                .containsExactly(DUE.plusDays(1), DUE.plusDays(2), DUE.plusDays(3));
        assertThat(accrual.charges()).extracting(PenaltyPolicy.DayCharge::amount)
                .allSatisfy(amount -> assertThat(amount).isEqualByComparingTo("5.25"));
        assertThat(accrual.total()).isEqualByComparingTo("15.75");
        assertThat(accrual.through()).isEqualTo(DUE.plusDays(3));
        assertThat(accrual.capReached()).isFalse();

        installment.accruePenalty(accrual.through(), accrual.total());
        assertThat(POLICY.accrue(installment, DUE.plusDays(3))).as("same day again").isNull();
        PenaltyPolicy.Accrual next = POLICY.accrue(installment, DUE.plusDays(4));
        assertThat(next.charges()).singleElement().satisfies(c -> assertThat(c.date()).isEqualTo(DUE.plusDays(4)));
    }

    @Test
    void penaltyIsSimpleAndUsesTheBaseAfterPartialRepayments() {
        RepaymentInstallment installment = installment("10000.00", "500.00");
        installment.accruePenalty(DUE.plusDays(1), new BigDecimal("5.25"));
        installment.pay(new BigDecimal("3333.33"), new BigDecimal("500.00"), new BigDecimal("5.25"));

        PenaltyPolicy.Accrual accrual = POLICY.accrue(installment, DUE.plusDays(2));

        // 6,666.67 x 0.0005 = 3.333335 -> 3.33 (HALF_EVEN); the penalty itself is never a base.
        assertThat(accrual.base()).isEqualByComparingTo("6666.67");
        assertThat(accrual.total()).isEqualByComparingTo("3.33");
    }

    @Test
    void theCapStopsAccrualAndTheLastDayGetsOnlyTheRemainder() {
        RepaymentInstallment installment = installment("100.00", "5.00");
        PenaltyPolicy steep = new PenaltyPolicy(new BigDecimal("0.010000"), new BigDecimal("0.05"));

        // Cap 105.00 x 0.05 = 5.25; 1.05 per day reaches it after exactly 5 days.
        PenaltyPolicy.Accrual accrual = steep.accrue(installment, DUE.plusDays(30));

        assertThat(accrual.charges()).hasSize(5);
        assertThat(accrual.total()).isEqualByComparingTo("5.25");
        assertThat(accrual.capReached()).isTrue();
        installment.accruePenalty(accrual.through(), accrual.total());
        PenaltyPolicy.Accrual later = steep.accrue(installment, DUE.plusDays(31));
        assertThat(later.charges()).isEmpty();
        assertThat(later.capReached()).isTrue();

        RepaymentInstallment odd = installment("100.00", "3.00");
        // Cap 103.00 x 0.05 = 5.15; daily 1.03; the fifth day is charged 1.03, total 5.15.
        assertThat(steep.accrue(odd, DUE.plusDays(30)).total()).isEqualByComparingTo("5.15");
        PenaltyPolicy capped = new PenaltyPolicy(new BigDecimal("0.010000"), new BigDecimal("0.04"));
        // Cap 4.12; four days of 1.03 exactly.
        PenaltyPolicy.Accrual exact = capped.accrue(installment("100.00", "3.00"), DUE.plusDays(30));
        assertThat(exact.charges()).hasSize(4);
        assertThat(exact.total()).isEqualByComparingTo("4.12");
    }

    @Test
    void accruingTheSameDayTwiceIsRefused() {
        RepaymentInstallment installment = installment("100.00", "5.00");
        installment.accruePenalty(DUE.plusDays(2), new BigDecimal("0.10"));
        assertThatThrownBy(() -> installment.accruePenalty(DUE.plusDays(2), new BigDecimal("0.05")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void repaymentsSettlePenaltyThenFeeThenPrincipalOldestInstallmentFirst() {
        RepaymentInstallment first = new RepaymentInstallment(UUID.randomUUID(), UUID.randomUUID(), 1, DUE,
                new BigDecimal("100.00"), new BigDecimal("10.00"));
        RepaymentInstallment second = new RepaymentInstallment(UUID.randomUUID(), first.getAgreementId(), 2, DUE.plusDays(30),
                new BigDecimal("100.00"), new BigDecimal("10.00"));
        first.accruePenalty(DUE.plusDays(4), new BigDecimal("2.00"));

        List<RepaymentPlan.Allocation> partial = RepaymentPlan.allocate(List.of(first, second), new BigDecimal("5.00"));
        assertThat(partial).singleElement().satisfies(a -> {
            assertThat(a.penaltyPart()).isEqualByComparingTo("2.00");
            assertThat(a.feePart()).isEqualByComparingTo("3.00");
            assertThat(a.principalPart()).isEqualByComparingTo("0.00");
        });

        assertThatThrownBy(() -> RepaymentPlan.allocate(List.of(first, second), new BigDecimal("217.01")))
                .isInstanceOf(BusinessRuleViolationException.class);
        List<RepaymentPlan.Allocation> rest = RepaymentPlan.allocate(List.of(first, second), new BigDecimal("217.00"));
        assertThat(rest).hasSize(2);
        assertThat(first.remaining()).isEqualByComparingTo("0");
        assertThat(second.remaining()).isEqualByComparingTo("0");
        assertThat(first.statusOn(DUE.plusDays(5))).isEqualTo(RepaymentInstallment.Status.PAID);
    }

    @Test
    void unpaidPenaltyKeepsAnInstallmentOverdueAndTheAgreementOpen() {
        RepaymentInstallment installment = installment("100.00", "10.00");
        installment.accruePenalty(DUE.plusDays(1), new BigDecimal("0.06"));
        installment.pay(new BigDecimal("100.00"), new BigDecimal("10.00"), Money.ZERO);

        assertThat(installment.remaining()).isEqualByComparingTo("0.06");
        assertThat(installment.statusOn(DUE.plusDays(2))).isEqualTo(RepaymentInstallment.Status.OVERDUE);
    }

    private static RepaymentInstallment installment(String principal, String fee) {
        return new RepaymentInstallment(UUID.randomUUID(), UUID.randomUUID(), 1, DUE, new BigDecimal(principal), new BigDecimal(fee));
    }
}
