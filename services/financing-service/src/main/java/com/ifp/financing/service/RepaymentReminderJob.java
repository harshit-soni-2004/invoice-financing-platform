package com.ifp.financing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic overdue sweep: accrues penalty interest on overdue installments, then publishes repayment reminders.
 * Both steps are idempotent per installment (per day for penalty, per kind for reminders), so overlapping or repeated
 * sweeps are harmless, and a missed sweep is caught up by the next one.
 */
@Component
@ConditionalOnProperty(name = "financing.repayment-reminder-sweep-enabled", havingValue = "true", matchIfMissing = true)
class RepaymentReminderJob {

    private static final Logger log = LoggerFactory.getLogger(RepaymentReminderJob.class);
    private static final int BATCH_SIZE = 200;

    private final RepaymentReminderService reminders;
    private final PenaltyAccrualService penalties;

    RepaymentReminderJob(RepaymentReminderService reminders, PenaltyAccrualService penalties) {
        this.reminders = reminders;
        this.penalties = penalties;
    }

    @Scheduled(fixedDelayString = "${financing.repayment-reminder-sweep-interval}",
            initialDelayString = "${financing.repayment-reminder-sweep-interval}")
    void publishReminders() {
        try {
            int charged = penalties.accrueDuePenalties(BATCH_SIZE);
            if (charged > 0) {
                log.info("Accrued penalty interest on {} agreement(s)", charged);
            }
        } catch (RuntimeException e) {
            log.warn("Penalty accrual sweep failed: {}", e.getClass().getSimpleName());
        }
        try {
            int published;
            do {
                published = reminders.publishDueReminders(BATCH_SIZE);
                if (published > 0) {
                    log.info("Published {} repayment reminder(s)", published);
                }
            } while (published == BATCH_SIZE);
        } catch (RuntimeException e) {
            log.warn("Repayment reminder sweep failed: {}", e.getClass().getSimpleName());
        }
    }
}
