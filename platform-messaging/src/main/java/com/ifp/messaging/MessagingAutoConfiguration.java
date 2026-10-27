package com.ifp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;

/**
 * Wires the shared topology, the outbox writer and relay, and the idempotent consumer support into any service
 * that has this module on its classpath. Each service owns its {@code outbox_events} and {@code processed_events}
 * tables through its own Flyway migrations.
 */
@AutoConfiguration(after = {RabbitAutoConfiguration.class, JdbcTemplateAutoConfiguration.class, TransactionAutoConfiguration.class})
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    @Bean
    Declarables ifpEventTopology() {
        return EventTopology.shared();
    }

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, ObjectProvider<Clock> clock,
                              MessagingProperties properties, Environment environment) {
        String producer = properties.producer() != null ? properties.producer()
                : environment.getProperty("spring.application.name", "unknown-service");
        return new OutboxWriter(jdbc, objectMapper, clock.getIfAvailable(Clock::systemUTC), producer);
    }

    @Bean
    @ConditionalOnProperty(name = "ifp.messaging.relay.enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, RabbitTemplate rabbit,
                            ObjectProvider<Clock> clock, MessagingProperties properties) {
        return new OutboxRelay(jdbc, new TransactionTemplate(transactionManager), rabbit,
                clock.getIfAvailable(Clock::systemUTC), properties.relay());
    }

    @Bean
    @ConditionalOnExpression("${ifp.messaging.relay.enabled:true} and ${ifp.messaging.purge.enabled:true}")
    OutboxPurger outboxPurger(JdbcTemplate jdbc, ObjectProvider<Clock> clock, MessagingProperties properties) {
        return new OutboxPurger(jdbc, clock.getIfAvailable(Clock::systemUTC), properties.purge());
    }

    @Bean
    InboundEventProcessor inboundEventProcessor(ObjectMapper objectMapper, JdbcTemplate jdbc,
                                                PlatformTransactionManager transactionManager, ObjectProvider<Clock> clock) {
        return new InboundEventProcessor(objectMapper, jdbc, new TransactionTemplate(transactionManager),
                clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * Listener retries skip errors that can never succeed (malformed or unsupported messages), so those go to the
     * dead-letter queue at once; everything else is retried with the configured backoff before dead-lettering.
     */
    @Bean
    RabbitRetryTemplateCustomizer ifpListenerRetryClassifier(RabbitProperties rabbitProperties) {
        return (target, retryTemplate) -> {
            if (target == RabbitRetryTemplateCustomizer.Target.LISTENER) {
                int maxAttempts = rabbitProperties.getListener().getSimple().getRetry().getMaxAttempts();
                retryTemplate.setRetryPolicy(retryPolicy(maxAttempts));
            }
        };
    }

    static SimpleRetryPolicy retryPolicy(int maxAttempts) {
        return new SimpleRetryPolicy(maxAttempts, Map.of(
                AmqpRejectAndDontRequeueException.class, false,
                MessageConversionException.class, false), true, true);
    }
}
