package com.ifp.financing.service;

import com.ifp.financing.config.FinancingProperties;
import com.ifp.messaging.Uuids;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Publishes "due soon" and "overdue" reminders for unpaid installments of disbursed agreements. Each (installment,
 * kind) is recorded in repayment_reminders in the same transaction as its outbox event, so a reminder goes out at most
 * once even when sweeps overlap; an installment first seen after its due date only gets the overdue reminder.
 */
@Service
public class RepaymentReminderService {

    public enum Kind { DUE_SOON, OVERDUE }

    record DueInstallment(UUID installmentId, UUID agreementId, UUID invoiceId, UUID sellerId, UUID financierId,
                          int sequenceNo, LocalDate dueDate) {
    }

    private final JdbcTemplate jdbc;
    private final FinancingEvents events;
    private final FinancingProperties properties;
    private final Clock clock;

    RepaymentReminderService(JdbcTemplate jdbc, FinancingEvents events, FinancingProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** Publishes up to {@code batchSize} reminders and returns how many were published. */
    @Transactional
    public int publishDueReminders(int batchSize) {
        LocalDate today = LocalDate.now(clock.withZone(properties.businessZone()));
        List<DueInstallment> due = jdbc.query("""
                        select i.id, i.agreement_id, a.invoice_id, a.seller_id, a.financier_id, i.sequence_no, i.due_date
                        from repayment_installments i
                        join financing_agreements a on a.id = i.agreement_id
                        where a.status in ('DISBURSED', 'REPAYMENT_IN_PROGRESS')
                          and i.principal_paid + i.fee_paid < i.principal_due + i.fee_due
                          and i.due_date <= ?
                          and not exists (select 1 from repayment_reminders r where r.installment_id = i.id
                                          and r.kind = case when i.due_date < ? then 'OVERDUE' else 'DUE_SOON' end)
                        order by i.due_date, i.id
                        limit ?""",
                (rs, n) -> new DueInstallment(Uuids.fromBytes(rs.getBytes(1)), Uuids.fromBytes(rs.getBytes(2)),
                        Uuids.fromBytes(rs.getBytes(3)), Uuids.fromBytes(rs.getBytes(4)), Uuids.fromBytes(rs.getBytes(5)),
                        rs.getInt(6), rs.getDate(7).toLocalDate()),
                Date.valueOf(today.plusDays(properties.repaymentReminderDays())), Date.valueOf(today), batchSize);
        int published = 0;
        for (DueInstallment installment : due) {
            Kind kind = installment.dueDate().isBefore(today) ? Kind.OVERDUE : Kind.DUE_SOON;
            try {
                jdbc.update("insert into repayment_reminders (installment_id, kind, created_at) values (?, ?, ?)",
                        Uuids.toBytes(installment.installmentId()), kind.name(), Timestamp.from(clock.instant()));
            } catch (DuplicateKeyException concurrentSweep) {
                continue;
            }
            events.repaymentReminder(installment, kind);
            published++;
        }
        return published;
    }
}
