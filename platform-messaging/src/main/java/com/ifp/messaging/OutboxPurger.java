package com.ifp.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deletes outbox rows that the broker confirmed longer ago than the retention period. Unpublished rows are never
 * touched, whatever their age. Deletes run in small batches so they do not hold long locks next to the relay.
 */
public class OutboxPurger implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxPurger.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final MessagingProperties.Purge settings;
    private volatile ScheduledExecutorService executor;

    public OutboxPurger(JdbcTemplate jdbc, Clock clock, MessagingProperties.Purge settings) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
    }

    /** Deletes every published row older than the retention and returns how many were removed. */
    public int purgePublished() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(settings.retention()));
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("delete from outbox_events where published_at < ? order by seq limit ?",
                    cutoff, settings.batchSize());
            total += deleted;
        } while (deleted == settings.batchSize());
        return total;
    }

    private void pass() {
        try {
            int deleted = purgePublished();
            if (deleted > 0) {
                log.info("Purged {} published outbox event(s)", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox purge failed: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-purge");
            thread.setDaemon(true);
            return thread;
        });
        long interval = settings.interval().toMillis();
        scheduler.scheduleWithFixedDelay(this::pass, interval, interval, TimeUnit.MILLISECONDS);
        executor = scheduler;
    }

    @Override
    public void stop() {
        ScheduledExecutorService scheduler = executor;
        executor = null;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
