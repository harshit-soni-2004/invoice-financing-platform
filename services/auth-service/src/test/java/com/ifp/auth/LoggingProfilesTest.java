package com.ifp.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ifp.common.web.CorrelationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots only the logging system with the service's real configuration files, so the effective console format of
 * the default and the {@code local} profile is verified without a database or broker.
 */
@ExtendWith(OutputCaptureExtension.class)
class LoggingProfilesTest {

    @Test
    void defaultProfileWritesJsonWithServiceNameAndCorrelationId(CapturedOutput output) throws Exception {
        List<String> lines = logWithCorrelationId(output, "json-check-12345");

        JsonNode entry = new ObjectMapper().readTree(lines.get(0));
        assertThat(entry.at("/service/name").asText()).isEqualTo("auth-service");
        assertThat(entry.path("correlationId").asText()).isEqualTo("json-check-12345");
        assertThat(entry.path("message").asText()).isEqualTo("profile check");
    }

    @Test
    void localProfileWritesHumanReadableLinesWithCorrelationId(CapturedOutput output) {
        List<String> lines = logWithCorrelationId(output, "local-check-12345", "local");

        assertThat(lines.get(0)).doesNotStartWith("{").contains("[local-check-12345]").contains("profile check");
    }

    private static List<String> logWithCorrelationId(CapturedOutput output, String correlationId, String... profiles) {
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles(profiles);
        try (ConfigurableApplicationContext ignored = app.run()) {
            MDC.put(CorrelationId.MDC_KEY, correlationId);
            try {
                LoggerFactory.getLogger(LoggingProfilesTest.class).info("profile check");
            } finally {
                MDC.remove(CorrelationId.MDC_KEY);
            }
        }
        List<String> lines = output.getOut().lines().filter(line -> line.contains(correlationId)).toList();
        assertThat(lines).hasSize(1);
        return lines;
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }
}
