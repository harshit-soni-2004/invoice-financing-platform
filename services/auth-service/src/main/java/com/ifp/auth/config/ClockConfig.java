package com.ifp.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** Ticks in whole microseconds to match DATETIME(6), which would otherwise round nanoseconds. */
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
