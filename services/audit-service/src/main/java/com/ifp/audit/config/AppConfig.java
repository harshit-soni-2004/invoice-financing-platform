package com.ifp.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
