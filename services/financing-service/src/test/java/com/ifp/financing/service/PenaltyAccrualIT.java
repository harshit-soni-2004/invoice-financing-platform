package com.ifp.financing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.domain.FinancingAgreement;
import com.ifp.financing.repository.FinancingAgreementRepository;
import com.ifp.financing.security.Actor;
import com.ifp.financing.security.Role;
import com.ifp.financing.support.AbstractIntegrationTest;
import com.ifp.financing.support.TestJwtSupport;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Penalty interest accrued by the overdue sweep (application.yml: 0.05% per day, capped at 10% per installment). */
class PenaltyAccrualIT extends AbstractIntegrationTest {

    private static final BigDecimal DAILY_RATE = new BigDecimal("0.000500");
    private static final BigDecimal CAP_RATIO = new BigDecimal("0.10");

    @Autowired
    private PenaltyAccrualService penalties;
    @Autowired
    private AgreementService agreementService;
    @Autowired
    private FinancingAgreementRepository agreements;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void accruesOncePerInstallmentPerDayAndCatchesUpMissedDays() throws Exception {
        Deal deal = disbursedDeal();
        Installment first = installment(deal.agreementId(), 1);
        BigDecimal daily = first.scheduled().multiply(DAILY_RATE).setScale(2, RoundingMode.HALF_EVEN);

        assertThat(penalties.accrue(deal.agreementId(), first.dueDate())).as("not overdue on the due date").isZero();
        moveTo(first.dueDate().plusDays(3));
        penalties.accrueDuePenalties(50);
        penalties.accrueDuePenalties(50);
        assertThat(penalties.accrue(deal.agreementId(), today())).as("same day again").isZero();

        assertThat(accrualDays(first.id())).containsExactly(first.dueDate().plusDays(1), first.dueDate().plusDays(2),
                first.dueDate().plusDays(3));
        assertThat(penaltyAccrued(first.id())).isEqualByComparingTo(daily.multiply(BigDecimal.valueOf(3)));
        FinancingAgreement agreement = agreements.findById(deal.agreementId()).orElseThrow();
        assertThat(agreement.getPenaltyAccrued()).isEqualByComparingTo(daily.multiply(BigDecimal.valueOf(3)));
        assertThat(agreement.outstanding()).isEqualByComparingTo(agreement.getTotalRepayable().add(agreement.getPenaltyAccrued()));

        List<JsonNode> events = penaltyEvents(deal.agreementId());
        assertThat(events).hasSize(1);
        JsonNode payload = events.getFirst().path("payload");
        assertThat(events.getFirst().path("actorId").isNull()).isTrue();
        assertThat(payload.path("amount").decimalValue()).isEqualByComparingTo(daily.multiply(BigDecimal.valueOf(3)));
        assertThat(payload.path("accrualDate").asText()).isEqualTo(today().toString());
        assertThat(payload.path("dailyRate").decimalValue()).isEqualByComparingTo(DAILY_RATE);
        assertThat(payload.path("sellerId").asText()).isEqualTo(deal.sellerId().toString());
        assertThat(payload.path("installments")).singleElement().satisfies(line -> {
            assertThat(line.path("sequenceNo").asInt()).isEqualTo(1);
            assertThat(line.path("days").asInt()).isEqualTo(3);
            assertThat(line.path("fromDate").asText()).isEqualTo(first.dueDate().plusDays(1).toString());
            assertThat(line.path("capReached").asBoolean()).isFalse();
        });

        clock.advance(Duration.ofDays(1));
        penalties.accrueDuePenalties(50);
        assertThat(accrualDays(first.id())).hasSize(4);
        assertThat(penaltyEvents(deal.agreementId())).hasSize(2);
    }

    @Test
    void penaltyStopsAtTheCapPerInstallment() throws Exception {
        Deal deal = disbursedDeal();
        Installment first = installment(deal.agreementId(), 1);
        Installment second = installment(deal.agreementId(), 2);
        moveTo(second.dueDate().plusDays(400));

        penalties.accrue(deal.agreementId(), today());

        for (Installment installment : List.of(first, second)) {
            BigDecimal cap = installment.scheduled().multiply(CAP_RATIO).setScale(2, RoundingMode.DOWN);
            assertThat(penaltyAccrued(installment.id())).isEqualByComparingTo(cap);
            assertThat(jdbc.queryForObject("select sum(amount) from penalty_accruals where installment_id = UUID_TO_BIN(?)",
                    BigDecimal.class, installment.id().toString())).isEqualByComparingTo(cap);
        }
        assertThat(penaltyEvents(deal.agreementId()).getFirst().path("payload").path("installments"))
                .allSatisfy(line -> assertThat(line.path("capReached").asBoolean()).isTrue());

        clock.advance(Duration.ofDays(30));
        penalties.accrueDuePenalties(50);
        assertThat(penalties.accrue(deal.agreementId(), today())).isZero();
        assertThat(penaltyEvents(deal.agreementId())).hasSize(1);
    }

    @Test
    void repaymentsPayPenaltyFirstAndTheAgreementSettlesOnlyWithPenaltyPaid() throws Exception {
        Deal deal = disbursedDeal();
        Installment first = installment(deal.agreementId(), 1);
        moveTo(first.dueDate().plusDays(10));
        penalties.accrue(deal.agreementId(), today());
        BigDecimal penalty = penaltyAccrued(first.id());
        assertThat(penalty).isPositive();

        JsonNode partial = body(repay(deal.sellerId(), deal.agreementId(), "1.00", newKey()));
        assertThat(partial.path("penaltyComponent").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(partial.path("feeComponent").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(partial.path("principalComponent").decimalValue()).isEqualByComparingTo("0.00");

        // Paying only the scheduled principal + fee does not settle: the rest of the penalty is taken first.
        FinancingAgreement before = agreements.findById(deal.agreementId()).orElseThrow();
        BigDecimal scheduledLeft = before.getTotalRepayable().subtract(before.getPrincipalRepaid()).subtract(before.getFeeRepaid());
        JsonNode second = body(repay(deal.sellerId(), deal.agreementId(), scheduledLeft.toPlainString(), newKey()));
        assertThat(second.path("penaltyComponent").decimalValue()).isEqualByComparingTo(penalty.subtract(new BigDecimal("1.00")));
        FinancingAgreement unsettled = agreements.findById(deal.agreementId()).orElseThrow();
        assertThat(unsettled.getStatus()).as("displaced principal/fee keeps it open").isEqualTo(AgreementStatus.REPAYMENT_IN_PROGRESS);
        assertThat(unsettled.penaltyOutstanding()).isEqualByComparingTo("0.00");
        assertThat(unsettled.outstanding()).isEqualByComparingTo(penalty.subtract(new BigDecimal("1.00")));

        JsonNode agreementBody = body(mockMvc.perform(withToken(get("/api/financing/agreements/{id}", deal.agreementId()),
                TestJwtSupport.seller(deal.sellerId()))).andReturn());
        assertThat(agreementBody.path("penaltyRepaid").decimalValue()).isEqualByComparingTo(penalty);
        assertThat(repay(deal.sellerId(), deal.agreementId(), agreementBody.path("outstanding").decimalValue().toPlainString(), newKey())
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(agreements.findById(deal.agreementId()).orElseThrow().getStatus()).isEqualTo(AgreementStatus.SETTLED);
        assertConsistent(deal.agreementId());
    }

    @Test
    void payingTheReportedOutstandingSettlesAndEventsCarryThePenaltyAdditively() throws Exception {
        Deal deal = disbursedDeal();
        Installment first = installment(deal.agreementId(), 1);
        moveTo(first.dueDate().plusDays(5));
        penalties.accrue(deal.agreementId(), today());

        JsonNode view = body(mockMvc.perform(withToken(get("/api/financing/agreements/{id}", deal.agreementId()),
                TestJwtSupport.seller(deal.sellerId()))).andExpect(status().isOk()).andReturn());
        BigDecimal penalty = view.path("penaltyAccrued").decimalValue();
        assertThat(view.path("penaltyOutstanding").decimalValue()).isEqualByComparingTo(penalty);
        assertThat(view.path("overdue").asBoolean()).isTrue();
        assertThat(view.path("outstanding").decimalValue())
                .isEqualByComparingTo(view.path("totalRepayable").decimalValue().add(penalty));

        assertThat(repay(deal.sellerId(), deal.agreementId(), view.path("outstanding").decimalValue().toPlainString(), newKey())
                .getResponse().getStatus()).isEqualTo(201);

        FinancingAgreement settled = agreements.findById(deal.agreementId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(AgreementStatus.SETTLED);
        assertThat(settled.getPenaltyRepaid()).isEqualByComparingTo(penalty);
        assertThat(jdbc.queryForObject("""
                select sum(al.penalty_part) from repayment_allocations al join repayments r on r.id = al.repayment_id
                where r.agreement_id = UUID_TO_BIN(?)""", BigDecimal.class, deal.agreementId().toString()))
                .isEqualByComparingTo(penalty);

        JsonNode recorded = outboxPayload(deal.agreementId(), "FinancingRepaymentRecorded");
        assertThat(recorded.path("penaltyComponent").decimalValue()).isEqualByComparingTo(penalty);
        assertThat(recorded.path("amount").decimalValue()).isEqualByComparingTo(recorded.path("principalComponent").decimalValue()
                .add(recorded.path("feeComponent").decimalValue()).add(penalty));
        JsonNode settledEvent = outboxPayload(deal.agreementId(), "FinancingSettled");
        assertThat(settledEvent.fieldNames()).toIterable().contains("agreementId", "invoiceId", "sellerId", "financierId",
                "status", "previousStatus", "advanceAmount", "feeAmount", "totalRepayable", "principalRepaid", "feeRepaid",
                "currency", "maturityDate", "penaltyAccrued", "penaltyRepaid");
        assertThat(settledEvent.path("penaltyRepaid").decimalValue()).isEqualByComparingTo(penalty);

        clock.advance(Duration.ofDays(5));
        assertThat(penalties.accrue(deal.agreementId(), today())).as("settled agreements accrue nothing").isZero();
    }

    @Test
    void scheduleApiReturnsPenaltyPerInstallment() throws Exception {
        Deal deal = disbursedDeal();
        Installment first = installment(deal.agreementId(), 1);
        moveTo(first.dueDate().plusDays(2));
        penalties.accrue(deal.agreementId(), today());
        BigDecimal penalty = penaltyAccrued(first.id());

        JsonNode schedule = body(mockMvc.perform(withToken(get("/api/financing/agreements/{id}/schedule", deal.agreementId()),
                TestJwtSupport.financier(deal.financierId()))).andExpect(status().isOk()).andReturn());

        JsonNode one = schedule.get(0);
        assertThat(one.path("status").asText()).isEqualTo("OVERDUE");
        assertThat(one.path("penaltyAccrued").decimalValue()).isEqualByComparingTo(penalty);
        assertThat(one.path("penaltyPaid").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(one.path("penaltyRemaining").decimalValue()).isEqualByComparingTo(penalty);
        assertThat(one.path("penaltyAccruedThrough").asText()).isEqualTo(today().toString());
        assertThat(one.path("totalDue").decimalValue()).isEqualByComparingTo(first.scheduled());
        assertThat(one.path("remaining").decimalValue()).isEqualByComparingTo(first.scheduled().add(penalty));
        JsonNode two = schedule.get(1);
        assertThat(two.path("penaltyAccrued").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(two.path("penaltyAccruedThrough").isNull()).isTrue();
    }

    /** Several sweeps (several instances, or overlapping runs) racing on one agreement charge each day once. */
    @RepeatedTest(3)
    void concurrentSweepsChargeEachDayExactlyOnce() throws Exception {
        Deal deal = disbursedDeal();
        Installment first = installment(deal.agreementId(), 1);
        BigDecimal daily = first.scheduled().multiply(DAILY_RATE).setScale(2, RoundingMode.HALF_EVEN);
        moveTo(first.dueDate().plusDays(5));
        LocalDate today = today();

        List<Callable<Object>> sweeps = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sweeps.add(() -> penalties.accrue(deal.agreementId(), today));
        }
        List<Outcome> outcomes = race(sweeps);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(outcomes.stream().map(o -> (BigDecimal) o.value()).filter(v -> v.signum() > 0))
                .singleElement().satisfies(v -> assertThat(v).isEqualByComparingTo(daily.multiply(BigDecimal.valueOf(5))));
        assertThat(accrualDays(first.id())).hasSize(5).doesNotHaveDuplicates();
        assertThat(penaltyEvents(deal.agreementId())).hasSize(1);
        assertConsistent(deal.agreementId());
    }

    @RepeatedTest(3)
    void accrualRacingRepaymentsKeepsEveryTotalConsistent() throws Exception {
        Deal deal = disbursedDeal();
        Installment first = installment(deal.agreementId(), 1);
        moveTo(first.dueDate().plusDays(5));
        LocalDate today = today();
        Actor seller = new Actor(deal.sellerId(), Set.of(Role.SELLER));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> penalties.accrue(deal.agreementId(), today));
            tasks.add(() -> agreementService.recordRepayment(seller, deal.agreementId(), new BigDecimal("1000.00"), newKey()));
        }
        List<Outcome> outcomes = race(tasks);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(accrualDays(first.id())).hasSize(5);
        assertThat(penaltyEvents(deal.agreementId())).hasSize(1);
        assertThat(jdbc.queryForObject("select sum(amount) from repayments where agreement_id = UUID_TO_BIN(?)",
                BigDecimal.class, deal.agreementId().toString())).isEqualByComparingTo("3000.00");
        assertConsistent(deal.agreementId());
    }

    /** Agreement totals = sum of installments = sum of accrual rows; repaid penalty = sum of allocation penalty parts. */
    private void assertConsistent(UUID agreementId) {
        String id = agreementId.toString();
        FinancingAgreement agreement = agreements.findById(agreementId).orElseThrow();
        BigDecimal installmentsAccrued = jdbc.queryForObject(
                "select sum(penalty_accrued) from repayment_installments where agreement_id = UUID_TO_BIN(?)", BigDecimal.class, id);
        BigDecimal installmentsPaid = jdbc.queryForObject(
                "select sum(penalty_paid) from repayment_installments where agreement_id = UUID_TO_BIN(?)", BigDecimal.class, id);
        BigDecimal rows = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from penalty_accruals where agreement_id = UUID_TO_BIN(?)", BigDecimal.class, id);
        BigDecimal allocated = jdbc.queryForObject("""
                select coalesce(sum(al.penalty_part), 0) from repayment_allocations al join repayments r on r.id = al.repayment_id
                where r.agreement_id = UUID_TO_BIN(?)""", BigDecimal.class, id);
        assertThat(agreement.getPenaltyAccrued()).isEqualByComparingTo(installmentsAccrued).isEqualByComparingTo(rows);
        assertThat(agreement.getPenaltyRepaid()).isEqualByComparingTo(installmentsPaid).isEqualByComparingTo(allocated);
    }

    private record Installment(UUID id, LocalDate dueDate, BigDecimal scheduled) {
    }

    private Installment installment(UUID agreementId, int sequenceNo) {
        return jdbc.queryForObject("""
                        select BIN_TO_UUID(id), due_date, principal_due + fee_due from repayment_installments
                        where agreement_id = UUID_TO_BIN(?) and sequence_no = ?""",
                (rs, n) -> new Installment(UUID.fromString(rs.getString(1)), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3)),
                agreementId.toString(), sequenceNo);
    }

    private void moveTo(LocalDate day) {
        clock.advance(Duration.ofDays(ChronoUnit.DAYS.between(today(), day)));
    }

    private BigDecimal penaltyAccrued(UUID installmentId) {
        return jdbc.queryForObject("select penalty_accrued from repayment_installments where id = UUID_TO_BIN(?)",
                BigDecimal.class, installmentId.toString());
    }

    private List<LocalDate> accrualDays(UUID installmentId) {
        return jdbc.query("select accrual_date from penalty_accruals where installment_id = UUID_TO_BIN(?) order by accrual_date",
                (rs, n) -> rs.getDate(1).toLocalDate(), installmentId.toString());
    }

    private List<JsonNode> penaltyEvents(UUID agreementId) {
        return jdbc.query("""
                        select envelope from outbox_events where aggregate_id = ? and event_type = 'FinancingPenaltyAccrued'
                        order by seq""",
                (rs, n) -> read(rs.getString(1)), agreementId.toString());
    }

    private JsonNode outboxPayload(UUID agreementId, String eventType) {
        return jdbc.query("select envelope from outbox_events where aggregate_id = ? and event_type = ? order by seq desc limit 1",
                (rs, n) -> read(rs.getString(1)), agreementId.toString(), eventType).getFirst().path("payload");
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Outcome(Object value, Throwable error) {
    }

    private static List<Outcome> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Outcome(task.call(), null);
                    } catch (Exception e) {
                        return new Outcome(null, e);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
