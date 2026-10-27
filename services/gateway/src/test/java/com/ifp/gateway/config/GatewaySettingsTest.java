package com.ifp.gateway.config;

import com.ifp.gateway.ratelimit.FailureMode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySettingsTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsAreValid() {
        assertThat(validator.validate(settings(List.of("http://localhost:4200"), limit(1, 60, 12)))).isEmpty();
    }

    @Test
    void wildcardOriginsAreRejected() {
        assertThat(messages(settings(List.of("*"), limit(1, 60, 12)))).anyMatch(m -> m.contains("explicit origins"));
        assertThat(messages(settings(List.of("https://*.example.com"), limit(1, 60, 12)))).anyMatch(m -> m.contains("explicit origins"));
    }

    @Test
    void limitThatCanNeverPassIsRejected() {
        assertThat(messages(settings(List.of("http://localhost:4200"), limit(1, 5, 10)))).anyMatch(m -> m.contains("burst-capacity"));
    }

    @Test
    void zeroRatesAreRejected() {
        assertThat(validator.validate(settings(List.of("http://localhost:4200"), limit(0, 60, 12)))).isNotEmpty();
    }

    private Set<String> messages(GatewaySettings settings) {
        return validator.validate(settings).stream().map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static GatewaySettings.Limit limit(int rate, int burst, int requested) {
        return new GatewaySettings.Limit(rate, burst, requested, FailureMode.FAIL_CLOSED);
    }

    private static GatewaySettings settings(List<String> origins, GatewaySettings.Limit limit) {
        return new GatewaySettings(
                new GatewaySettings.Cors(origins, List.of("GET"), List.of("Authorization"), List.of(), Duration.ofHours(1)),
                new GatewaySettings.RateLimit(limit, limit, Duration.ofMillis(500)),
                new GatewaySettings.ClientIp(0));
    }
}
