package com.ifp.invoice.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.ZoneId;

/**
 * Configurable invoice rules.
 *
 * @param businessZone                    zone that defines "today" for calendar-date rules (timestamps are stored in UTC)
 * @param maxAmount                       largest invoice amount accepted, in INR
 * @param maxTenorDays                    longest allowed period between issue date and due date
 * @param minDaysToMaturityAtSubmission   minimum days left until the due date when submitting or listing
 */
@Validated
@ConfigurationProperties(prefix = "invoice")
public record InvoiceProperties(
        @NotNull ZoneId businessZone,
        @NotNull @Positive BigDecimal maxAmount,
        @Positive int maxTenorDays,
        @PositiveOrZero int minDaysToMaturityAtSubmission) {
}
