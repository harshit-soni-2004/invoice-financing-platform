package com.ifp.invoice.domain;

import com.ifp.invoice.config.InvoiceProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Configurable business rules on invoice amounts and dates. */
@Component
public class InvoicePolicy {

    private final InvoiceProperties properties;
    private final Clock clock;

    public InvoicePolicy(InvoiceProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void checkDetails(InvoiceDetails details) {
        if (details.amount().signum() <= 0) {
            throw new BusinessRuleViolationException("AMOUNT_NOT_POSITIVE", "Invoice amount must be positive");
        }
        if (details.amount().compareTo(properties.maxAmount()) > 0) {
            throw new BusinessRuleViolationException("AMOUNT_TOO_LARGE",
                    "Invoice amount exceeds the maximum of " + properties.maxAmount().toPlainString() + " INR");
        }
        LocalDate today = today();
        if (details.issueDate().isAfter(today)) {
            throw new BusinessRuleViolationException("ISSUE_DATE_IN_FUTURE", "Issue date cannot be in the future");
        }
        if (!details.dueDate().isAfter(details.issueDate())) {
            throw new BusinessRuleViolationException("DUE_DATE_NOT_AFTER_ISSUE_DATE", "Due date must be after the issue date");
        }
        long tenorDays = ChronoUnit.DAYS.between(details.issueDate(), details.dueDate());
        if (tenorDays > properties.maxTenorDays()) {
            throw new BusinessRuleViolationException("TENOR_TOO_LONG",
                    "Due date must be within " + properties.maxTenorDays() + " days of the issue date");
        }
    }

    /** Applies when submitting for verification and when listing for financing. */
    public void checkTimeToMaturity(Invoice invoice) {
        LocalDate earliestDueDate = today().plusDays(properties.minDaysToMaturityAtSubmission());
        if (invoice.getDueDate().isBefore(earliestDueDate)) {
            throw new BusinessRuleViolationException("DUE_DATE_TOO_SOON",
                    "Due date must be at least " + properties.minDaysToMaturityAtSubmission() + " days away");
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(properties.businessZone()));
    }
}
