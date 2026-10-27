package com.ifp.financing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(FinancingProperties.class)
public class ClockConfig {

    /**
     * Ticks in whole microseconds to match DATETIME(6); MySQL would otherwise round nanoseconds,
     * making stored timestamps differ from the values returned to clients.
     */
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
