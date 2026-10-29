package com.ifp.financing.service;

import com.ifp.financing.config.FinancingProperties;
import com.ifp.financing.domain.FinancingAgreement;
import com.ifp.financing.domain.Money;
import com.ifp.financing.domain.PenaltyPolicy;
import com.ifp.financing.domain.RepaymentInstallment;
import com.ifp.financing.repository.FinancingAgreementRepository;
import com.ifp.financing.repository.RepaymentInstallmentRepository;
import com.ifp.messaging.Uuids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Accrues penalty interest on overdue installments (rules in {@link PenaltyPolicy}). Each agreement is handled in its
 * own transaction that locks the agreement row and then its installments, the same order as repayments, so accrual
 * and repayments serialize without deadlocks and two overlapping sweeps cannot both charge a day: the second one
 * sees the first one's {@code penalty_accrued_through}. The {@code penalty_accruals} primary key (installment, day)
 * is the final guard. Balances, the accrual rows and the {@code FinancingPenaltyAccrued} outbox event commit together.
 */
@Service
public class PenaltyAccrualService {

    private static final Logger log = LoggerFactory.getLogger(PenaltyAccrualService.class);

    private final FinancingAgreementRepository agreements;
    private final RepaymentInstallmentRepository installments;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FinancingEvents events;
    private final FinancingProperties properties;
    private final Clock clock;

    PenaltyAccrualService(FinancingAgreementRepository agreements, RepaymentInstallmentRepository installments,
                          JdbcTemplate jdbc, PlatformTransactionManager transactionManager, FinancingEvents events,
                          FinancingProperties properties, Clock clock) {
        this.agreements = agreements;
        this.installments = installments;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Accrues penalty for every agreement with an overdue installment not yet accrued through today and below its cap.
     * Agreements are visited in ID order (keyset pagination), so one failing agreement cannot stall the sweep.
     * Returns the number of agreements charged.
     */
    public int accrueDuePenalties(int batchSize) {
        if (!properties.penalty().enabled()) {
            return 0;
        }
        LocalDate today = today();
        byte[] after = new byte[16];
        int charged = 0;
        while (true) {
            List<byte[]> ids = jdbc.query("""
                            select distinct a.id
                            from financing_agreements a
                            join repayment_installments i on i.agreement_id = a.id
                            where a.status in ('DISBURSED', 'REPAYMENT_IN_PROGRESS')
                              and i.due_date < ?
                              and i.principal_paid + i.fee_paid < i.principal_due + i.fee_due
                              and (i.penalty_accrued_through is null or i.penalty_accrued_through < ?)
                              and i.penalty_accrued < truncate((i.principal_due + i.fee_due) * ?, 2)
                              and a.id > ?
                            order by a.id
                            limit ?""",
                    (rs, n) -> rs.getBytes(1), Date.valueOf(today), Date.valueOf(today), properties.penalty().capRatio(),
                    after, batchSize);
            for (byte[] raw : ids) {
                UUID id = Uuids.fromBytes(raw);
                try {
                    if (accrue(id, today).signum() > 0) {
                        charged++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Penalty accrual failed for agreement {}: {}", id, e.getClass().getSimpleName());
                }
            }
            if (ids.size() < batchSize) {
                return charged;
            }
            after = ids.getLast();
        }
    }

    /** Accrues one agreement through {@code today} in its own transaction; returns the amount charged (often zero). */
    public BigDecimal accrue(UUID agreementId, LocalDate today) {
        BigDecimal charged = tx.execute(status -> doAccrue(agreementId, today));
        return charged == null ? Money.ZERO : charged;
    }

    private BigDecimal doAccrue(UUID agreementId, LocalDate today) {
        FinancingAgreement agreement = agreements.findForUpdate(agreementId).orElse(null);
        if (agreement == null || !agreement.getStatus().acceptsRepayments()) {
            return Money.ZERO;
        }
        PenaltyPolicy policy = properties.penalty().policy();
        Instant now = clock.instant();
        BigDecimal total = Money.ZERO;
        List<FinancingEvents.InstallmentPenalty> lines = new ArrayList<>();
        for (RepaymentInstallment installment : installments.findForUpdate(agreementId)) {
            PenaltyPolicy.Accrual accrual = policy.accrue(installment, today);
            if (accrual == null) {
                continue;
            }
            for (PenaltyPolicy.DayCharge charge : accrual.charges()) {
                jdbc.update("""
                                insert into penalty_accruals (installment_id, accrual_date, agreement_id, base_amount,
                                                              daily_rate, amount, created_at)
                                values (?, ?, ?, ?, ?, ?, ?)""",
                        Uuids.toBytes(installment.getId()), Date.valueOf(charge.date()), Uuids.toBytes(agreementId),
                        accrual.base(), policy.dailyRate(), charge.amount(), Timestamp.from(now));
            }
            BigDecimal amount = accrual.total();
            installment.accruePenalty(accrual.through(), amount);
            if (amount.signum() > 0) {
                total = total.add(amount);
                lines.add(new FinancingEvents.InstallmentPenalty(installment.getId(), installment.getSequenceNo(),
                        installment.getDueDate(), accrual.from(), accrual.charges().getLast().date(),
                        accrual.charges().size(), accrual.base(), amount, installment.getPenaltyAccrued(),
                        accrual.capReached()));
            }
        }
        if (total.signum() > 0) {
            agreement.accruePenalty(total, now);
            events.penaltyAccrued(agreement, today, policy.dailyRate(), total, lines);
            log.info("Accrued penalty {} on agreement {} through {}", total, agreementId, today);
        }
        return total;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(properties.businessZone()));
    }
}
