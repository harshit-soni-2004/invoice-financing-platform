package com.ifp.financing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically publishes repayment reminders. Missing a sweep only delays reminders; it never changes balances. */
@Component
@ConditionalOnProperty(name = "financing.repayment-reminder-sweep-enabled", havingValue = "true", matchIfMissing = true)
class RepaymentReminderJob {

    private static final Logger log = LoggerFactory.getLogger(RepaymentReminderJob.class);
    private static final int BATCH_SIZE = 200;

    private final RepaymentReminderService reminders;

    RepaymentReminderJob(RepaymentReminderService reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${financing.repayment-reminder-sweep-interval}",
            initialDelayString = "${financing.repayment-reminder-sweep-interval}")
    void publishReminders() {
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
