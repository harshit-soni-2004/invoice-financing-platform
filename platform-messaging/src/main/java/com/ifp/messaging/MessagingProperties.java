package com.ifp.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param producer        name written into every envelope (defaults to {@code spring.application.name})
 * @param relay           outbox relay settings
 * @param purge           clean-up of published outbox rows
 * @param processedEvents retention of consumer de-duplication rows
 */
@ConfigurationProperties("ifp.messaging")
public record MessagingProperties(String producer, @DefaultValue Relay relay, @DefaultValue Purge purge,
                                  @DefaultValue ProcessedEvents processedEvents) {

    /**
     * Only active where the relay is, since services without a relay have no outbox table.
     *
     * @param enabled   whether published rows are deleted at all
     * @param retention how long a published row is kept (for inspection and replay) before it is deleted
     * @param interval  pause between purge passes
     * @param batchSize rows deleted per statement
     */
    public record Purge(@DefaultValue("true") boolean enabled,
                        @DefaultValue("7d") Duration retention,
                        @DefaultValue("1h") Duration interval,
                        @DefaultValue("1000") int batchSize) {
    }

    /**
     * @param enabled        whether this service publishes its outbox
     * @param interval       pause between relay passes when the outbox is drained
     * @param batchSize      rows claimed per pass
     * @param confirmTimeout how long to wait for a broker confirm before treating the publish as failed
     * @param maxBackoff     upper bound of the exponential retry delay for a failed row
     */
    public record Relay(@DefaultValue("true") boolean enabled,
                        @DefaultValue("500ms") Duration interval,
                        @DefaultValue("50") int batchSize,
                        @DefaultValue("5s") Duration confirmTimeout,
                        @DefaultValue("5m") Duration maxBackoff) {
    }

    /**
     * Active in every service with this module; a no-op where there is no {@code processed_events} table.
     *
     * @param enabled       whether old rows are deleted at all
     * @param retentionDays rows whose {@code processed_at} is older than this many days are deleted; a redelivery of
     *                      such an event would be applied again, so keep it well beyond any replay window
     * @param interval      pause between purge passes
     * @param batchSize     rows deleted per statement
     */
    public record ProcessedEvents(@DefaultValue("true") boolean enabled,
                                  @DefaultValue("30") int retentionDays,
                                  @DefaultValue("1h") Duration interval,
                                  @DefaultValue("1000") int batchSize) {
        public ProcessedEvents {
            if (retentionDays < 1) {
                throw new IllegalArgumentException("ifp.messaging.processed-events.retention-days must be at least 1");
            }
            if (batchSize < 1 || batchSize > 10_000) {
                throw new IllegalArgumentException("ifp.messaging.processed-events.batch-size must be between 1 and 10000");
            }
        }
    }
}
