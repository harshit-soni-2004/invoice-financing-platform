package com.ifp.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deletes consumer de-duplication rows ({@code processed_events}) older than the retention period. Once a row is gone
 * a redelivery of that event would be applied again, so the retention must stay far longer than any redelivery path
 * (delayed retries take minutes; replays from a DLQ or an outbox should happen within the retention).
 * <p>
 * Each batch first reads candidate keys with a plain (non-locking) read and then deletes them by primary key, so the
 * delete never range-locks rows that live consumers are inserting, even where {@code processed_at} has no index.
 * Services without the table (audit-service) are detected once and skipped.
 */
public class ProcessedEventsPurger implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventsPurger.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final MessagingProperties.ProcessedEvents settings;
    private final Counter purged;
    private final Counter failures;
    private volatile Boolean tablePresent;
    private volatile ScheduledExecutorService executor;

    public ProcessedEventsPurger(JdbcTemplate jdbc, Clock clock, MessagingProperties.ProcessedEvents settings, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.purged = Counter.builder("ifp.messaging.processed.events.purged")
                .description("processed_events rows deleted by the retention job").register(meters);
        this.failures = Counter.builder("ifp.messaging.processed.events.purge.failures")
                .description("processed_events retention passes that failed").register(meters);
    }

    /** Deletes every row older than the retention and returns how many were removed. */
    public int purgeExpired() {
        if (!tablePresent()) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(settings.retentionDays()));
        Timestamp cutoffTs = Timestamp.from(cutoff);
        int total = 0;
        while (true) {
            List<Object[]> keys = jdbc.query("select consumer, event_id from processed_events where processed_at < ? limit ?",
                    (rs, n) -> new Object[]{rs.getString(1), rs.getBytes(2)}, cutoffTs, settings.batchSize());
            if (keys.isEmpty()) {
                break;
            }
            List<Object> args = new ArrayList<>(keys.size() * 2 + 1);
            args.add(cutoffTs);
            StringBuilder tuples = new StringBuilder();
            for (Object[] key : keys) {
                tuples.append(tuples.isEmpty() ? "(?, ?)" : ", (?, ?)");
                args.add(key[0]);
                args.add(key[1]);
            }
            int deleted = jdbc.update("delete from processed_events where processed_at < ? and (consumer, event_id) in ("
                    + tuples + ")", args.toArray());
            total += deleted;
            purged.increment(deleted);
            if (keys.size() < settings.batchSize() || deleted == 0) {
                break;
            }
        }
        if (total > 0) {
            log.info("Purged {} processed_events row(s) older than {} day(s) (before {})", total, settings.retentionDays(), cutoff);
        } else {
            log.debug("No processed_events rows older than {} day(s)", settings.retentionDays());
        }
        return total;
    }

    boolean tablePresent() {
        Boolean present = tablePresent;
        if (present == null) {
            Integer count = jdbc.queryForObject("""
                    select count(*) from information_schema.tables
                    where table_schema = database() and table_name = 'processed_events'""", Integer.class);
            present = count != null && count > 0;
            tablePresent = present;
            if (!present) {
                log.info("No processed_events table in this service; processed-event retention is inactive");
            }
        }
        return present;
    }

    private void pass() {
        try {
            purgeExpired();
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("processed_events purge failed: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "processed-events-purge");
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
