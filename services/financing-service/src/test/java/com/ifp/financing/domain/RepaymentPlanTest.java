package com.ifp.financing.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepaymentPlanTest {

    private static final UUID AGREEMENT = UUID.randomUUID();
    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    @Test
    void splitPutsRemainderOnLastPart() {
        assertThat(RepaymentPlan.split(new BigDecimal("100.00"), 3))
                .containsExactly(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34"));
        assertThat(RepaymentPlan.split(new BigDecimal("0.05"), 3))
                .containsExactly(new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("0.03"));
    }

    @Test
    void scheduleSumsExactlyAndEndsOnMaturity() {
        List<RepaymentInstallment> schedule = RepaymentPlan.schedule(AGREEMENT, START, START.plusDays(60), 3,
                new BigDecimal("480000.00"), new BigDecimal("10468.49"), UUID::randomUUID);

        assertThat(schedule).hasSize(3);
        assertThat(schedule).extracting(RepaymentInstallment::getSequenceNo).containsExactly(1, 2, 3);
        assertThat(schedule).extracting(RepaymentInstallment::getDueDate)
                .containsExactly(START.plusDays(20), START.plusDays(40), START.plusDays(60));
        assertThat(schedule.stream().map(RepaymentInstallment::getPrincipalDue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("480000.00");
        assertThat(schedule.stream().map(RepaymentInstallment::getFeeDue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("10468.49");
        assertThat(schedule.get(2).getFeeDue()).isEqualByComparingTo("3489.51");
    }

    @Test
    void dueDatesRoundUpWhenDaysDoNotDivideEvenly() {
        List<RepaymentInstallment> schedule = RepaymentPlan.schedule(AGREEMENT, START, START.plusDays(10), 3,
                new BigDecimal("300.00"), new BigDecimal("3.00"), UUID::randomUUID);
        // ceil(10/3)=4, ceil(20/3)=7, 10
        assertThat(schedule).extracting(RepaymentInstallment::getDueDate)
                .containsExactly(START.plusDays(4), START.plusDays(7), START.plusDays(10));
    }

    @Test
    void scheduleNeedsAtLeastOneDayPerInstallment() {
        assertThatThrownBy(() -> RepaymentPlan.schedule(AGREEMENT, START, START.plusDays(2), 3,
                new BigDecimal("300.00"), new BigDecimal("3.00"), UUID::randomUUID))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("installment");
    }

    @Test
    void allocationPaysOldestInstallmentFeeFirst() {
        List<RepaymentInstallment> schedule = twoInstallments();

        List<RepaymentPlan.Allocation> allocations = RepaymentPlan.allocate(schedule, new BigDecimal("30.00"));

        assertThat(allocations).hasSize(1);
        assertThat(allocations.get(0).feePart()).isEqualByComparingTo("10.00");
        assertThat(allocations.get(0).principalPart()).isEqualByComparingTo("20.00");
        assertThat(schedule.get(0).remaining()).isEqualByComparingTo("80.00");
        assertThat(schedule.get(1).remaining()).isEqualByComparingTo("110.00");
    }

    @Test
    void allocationSpillsIntoNextInstallment() {
        List<RepaymentInstallment> schedule = twoInstallments();

        List<RepaymentPlan.Allocation> allocations = RepaymentPlan.allocate(schedule, new BigDecimal("125.00"));

        assertThat(allocations).hasSize(2);
        assertThat(allocations.get(0).feePart()).isEqualByComparingTo("10.00");
        assertThat(allocations.get(0).principalPart()).isEqualByComparingTo("100.00");
        assertThat(allocations.get(1).feePart()).isEqualByComparingTo("10.00");
        assertThat(allocations.get(1).principalPart()).isEqualByComparingTo("5.00");
        assertThat(schedule.get(0).remaining()).isEqualByComparingTo("0.00");
        assertThat(schedule.get(1).remaining()).isEqualByComparingTo("95.00");
    }

    @Test
    void allocationOfFullOutstandingClearsEverything() {
        List<RepaymentInstallment> schedule = twoInstallments();

        RepaymentPlan.allocate(schedule, new BigDecimal("220.00"));

        assertThat(schedule).allSatisfy(i -> assertThat(i.remaining()).isEqualByComparingTo("0"));
    }

    @Test
    void allocationRejectsMoreThanOutstandingWithoutChangingAnything() {
        List<RepaymentInstallment> schedule = twoInstallments();

        assertThatThrownBy(() -> RepaymentPlan.allocate(schedule, new BigDecimal("220.01")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting("code").isEqualTo("REPAYMENT_EXCEEDS_OUTSTANDING");
        assertThat(schedule).allSatisfy(i -> assertThat(i.getPrincipalPaid()).isEqualByComparingTo("0"));
    }

    @Test
    void installmentStatusIsDerivedFromDateAndPayments() {
        RepaymentInstallment installment = new RepaymentInstallment(UUID.randomUUID(), AGREEMENT, 1, START,
                new BigDecimal("100.00"), new BigDecimal("10.00"));
        assertThat(installment.statusOn(START)).isEqualTo(RepaymentInstallment.Status.PENDING);
        assertThat(installment.statusOn(START.plusDays(1))).isEqualTo(RepaymentInstallment.Status.OVERDUE);
        installment.pay(Money.ZERO, new BigDecimal("10.00"));
        assertThat(installment.statusOn(START)).isEqualTo(RepaymentInstallment.Status.PARTIALLY_PAID);
        assertThat(installment.statusOn(START.plusDays(1))).isEqualTo(RepaymentInstallment.Status.OVERDUE);
        installment.pay(new BigDecimal("100.00"), Money.ZERO);
        assertThat(installment.statusOn(START.plusDays(1))).isEqualTo(RepaymentInstallment.Status.PAID);
    }

    private static List<RepaymentInstallment> twoInstallments() {
        return List.of(
                new RepaymentInstallment(UUID.randomUUID(), AGREEMENT, 1, START.plusDays(30), new BigDecimal("100.00"), new BigDecimal("10.00")),
                new RepaymentInstallment(UUID.randomUUID(), AGREEMENT, 2, START.plusDays(60), new BigDecimal("100.00"), new BigDecimal("10.00")));
    }
}
