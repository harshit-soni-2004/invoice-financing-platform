package com.ifp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.aopalliance.aop.Advice;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.BaseRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
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
import org.springframework.util.function.SingletonSupplier;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Wires the shared topology, the outbox writer, relay and purge, the idempotent consumer support with its
 * {@code processed_events} retention, and delayed retries into any service that has this module on its classpath.
 * Each service owns its {@code outbox_events} and {@code processed_events} tables through its own Flyway migrations.
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
    @ConditionalOnProperty(name = "ifp.messaging.processed-events.enabled", havingValue = "true", matchIfMissing = true)
    ProcessedEventsPurger processedEventsPurger(JdbcTemplate jdbc, ObjectProvider<Clock> clock, MessagingProperties properties,
                                                ObjectProvider<MeterRegistry> meters) {
        return new ProcessedEventsPurger(jdbc, clock.getIfAvailable(Clock::systemUTC), properties.processedEvents(),
                meters.getIfAvailable(() -> Metrics.globalRegistry));
    }

    /** Tiers are read lazily from the declared queues, so declarations added by any configuration class count. */
    @Bean
    DelayedRetryAdvice ifpDelayedRetryAdvice(ListableBeanFactory beans, ObjectProvider<RabbitTemplate> rabbit,
                                             MessagingProperties properties, ObjectProvider<MeterRegistry> meters) {
        Supplier<Map<String, List<DelayedRetryAdvice.Tier>>> tiers = SingletonSupplier.of(() -> {
            Map<String, List<DelayedRetryAdvice.Tier>> discovered = DelayedRetryAdvice.discover(
                    beans.getBeansOfType(Declarables.class, false, false).values(),
                    beans.getBeansOfType(Queue.class, false, false).values());
            discovered.forEach((queue, ladder) -> LoggerFactory.getLogger(DelayedRetryAdvice.class).info(
                    "Delayed retries for {}: {}", queue, ladder.stream().map(t -> t.delay().toString()).toList()));
            return discovered;
        });
        return new DelayedRetryAdvice(tiers, rabbit::getObject, properties.relay().confirmTimeout(),
                SingletonSupplier.of(() -> meters.getIfAvailable(() -> Metrics.globalRegistry)));
    }

    /**
     * Appends the delayed-retry advice as the innermost advice of every listener container factory, inside Boot's
     * stateless retry interceptor (when enabled): for queues with retry tiers it handles the failure itself, so
     * in-listener retries never run; for other queues it rethrows and behaviour is unchanged.
     */
    @Bean
    static BeanPostProcessor ifpDelayedRetryAdviceInstaller(ObjectProvider<DelayedRetryAdvice> advice) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof BaseRabbitListenerContainerFactory<?> factory) {
                    Advice[] existing = factory.getAdviceChain();
                    List<Advice> chain = new ArrayList<>(existing == null ? List.of() : Arrays.asList(existing));
                    chain.add(advice.getObject());
                    factory.setAdviceChain(chain.toArray(Advice[]::new));
                }
                return bean;
            }
        };
    }

    @Bean
    InboundEventProcessor inboundEventProcessor(ObjectMapper objectMapper, JdbcTemplate jdbc,
                                                PlatformTransactionManager transactionManager, ObjectProvider<Clock> clock) {
        return new InboundEventProcessor(objectMapper, jdbc, new TransactionTemplate(transactionManager),
                clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * For services that still use Boot's in-listener retry on queues without delay tiers: retries skip errors that can
     * never succeed (malformed or unsupported messages), so those go to the dead-letter queue at once; everything else
     * is retried with the configured backoff before dead-lettering.
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
