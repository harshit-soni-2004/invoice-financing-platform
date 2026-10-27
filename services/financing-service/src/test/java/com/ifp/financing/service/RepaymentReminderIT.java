package com.ifp.financing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifp.financing.repository.FinancingAgreementRepository;
import com.ifp.financing.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RepaymentReminderIT extends AbstractIntegrationTest {

    private static final int ALL = 10_000;

    @Autowired
    private RepaymentReminderService reminders;
    @Autowired
    private FinancingAgreementRepository agreements;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void eachInstallmentGetsOneDueSoonAndOneOverdueReminder() throws Exception {
        Deal deal = disbursedDeal();
        LocalDate firstDue = jdbc.queryForObject("""
                select due_date from repayment_installments where agreement_id = UUID_TO_BIN(?) and sequence_no = 1""",
                LocalDate.class, deal.agreementId().toString());

        reminders.publishDueReminders(ALL);
        assertThat(reminderEvents(deal.agreementId())).isEmpty();

        clock.advance(Duration.ofDays(ChronoUnit.DAYS.between(today(), firstDue) - 2));
        reminders.publishDueReminders(ALL);
        reminders.publishDueReminders(ALL);
        assertThat(reminderEvents(deal.agreementId())).extracting(e -> e.path("eventType").asText())
                .containsExactly("FinancingRepaymentDueSoon");

        clock.advance(Duration.ofDays(3));
        reminders.publishDueReminders(ALL);
        reminders.publishDueReminders(ALL);
        List<JsonNode> events = reminderEvents(deal.agreementId());
        assertThat(events).extracting(e -> e.path("eventType").asText())
                .containsExactly("FinancingRepaymentDueSoon", "FinancingRepaymentOverdue");

        JsonNode overdue = events.get(1);
        assertThat(overdue.path("actorId").isNull()).isTrue();
        assertThat(overdue.path("payload").path("sequenceNo").asInt()).isEqualTo(1);
        assertThat(overdue.path("payload").path("dueDate").asText()).isEqualTo(firstDue.toString());
        assertThat(overdue.path("payload").path("sellerId").asText()).isEqualTo(deal.sellerId().toString());
        assertThat(overdue.path("payload").has("amount")).isFalse();
    }

    @Test
    void settledAgreementsGetNoReminders() throws Exception {
        Deal deal = disbursedDeal();
        BigDecimal total = agreements.findById(deal.agreementId()).orElseThrow().getTotalRepayable();
        assertThat(repay(deal.sellerId(), deal.agreementId(), total.toPlainString(), newKey()).getResponse().getStatus())
                .isEqualTo(201);

        clock.advance(Duration.ofDays(90));
        reminders.publishDueReminders(ALL);

        assertThat(reminderEvents(deal.agreementId())).isEmpty();
    }

    private List<JsonNode> reminderEvents(UUID agreementId) {
        return jdbc.query("""
                        select envelope from outbox_events
                        where aggregate_id = ? and event_type in ('FinancingRepaymentDueSoon', 'FinancingRepaymentOverdue')
                        order by seq""",
                (rs, n) -> {
                    try {
                        return objectMapper.readTree(rs.getString(1));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, agreementId.toString());
    }
}
