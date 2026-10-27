package com.ifp.invoice.domain;

import com.ifp.invoice.config.InvoiceProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoicePolicyTest {

    // 2026-10-27T20:00Z is already 2026-10-28 in Asia/Kolkata (UTC+05:30).
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T20:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY_IST = LocalDate.parse("2026-10-28");

    private final InvoicePolicy policy = new InvoicePolicy(
            new InvoiceProperties(ZoneId.of("Asia/Kolkata"), new BigDecimal("100000000.00"), 180, 7), CLOCK);

    private static InvoiceDetails details(BigDecimal amount, LocalDate issue, LocalDate due) {
        return new InvoiceDetails("INV-1", "Buyer", null, null, amount, issue, due);
    }

    private static void assertRule(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessRuleViolationException.class).extracting("code").isEqualTo(code);
    }

    @Test
    void todayIsEvaluatedInBusinessZone() {
        assertThatCode(() -> policy.checkDetails(details(BigDecimal.TEN, TODAY_IST, TODAY_IST.plusDays(30))))
                .doesNotThrowAnyException();
        assertRule(() -> policy.checkDetails(details(BigDecimal.TEN, TODAY_IST.plusDays(1), TODAY_IST.plusDays(30))),
                "ISSUE_DATE_IN_FUTURE");
    }

    @Test
    void amountMustBePositiveAndWithinMaximum() {
        LocalDate issue = TODAY_IST.minusDays(1);
        assertRule(() -> policy.checkDetails(details(BigDecimal.ZERO, issue, issue.plusDays(30))), "AMOUNT_NOT_POSITIVE");
        assertRule(() -> policy.checkDetails(details(new BigDecimal("-1.00"), issue, issue.plusDays(30))), "AMOUNT_NOT_POSITIVE");
        assertRule(() -> policy.checkDetails(details(new BigDecimal("100000000.01"), issue, issue.plusDays(30))), "AMOUNT_TOO_LARGE");
        assertThatCode(() -> policy.checkDetails(details(new BigDecimal("100000000.00"), issue, issue.plusDays(30))))
                .doesNotThrowAnyException();
    }

    @Test
    void dueDateMustBeAfterIssueDateAndWithinTenor() {
        LocalDate issue = TODAY_IST.minusDays(10);
        assertRule(() -> policy.checkDetails(details(BigDecimal.TEN, issue, issue)), "DUE_DATE_NOT_AFTER_ISSUE_DATE");
        assertRule(() -> policy.checkDetails(details(BigDecimal.TEN, issue, issue.minusDays(1))), "DUE_DATE_NOT_AFTER_ISSUE_DATE");
        assertRule(() -> policy.checkDetails(details(BigDecimal.TEN, issue, issue.plusDays(181))), "TENOR_TOO_LONG");
        assertThatCode(() -> policy.checkDetails(details(BigDecimal.TEN, issue, issue.plusDays(180)))).doesNotThrowAnyException();
    }

    @Test
    void submissionRequiresMinimumDaysToMaturity() {
        Invoice tooSoon = Invoice.createDraft(UUID.randomUUID(), UUID.randomUUID(),
                details(BigDecimal.TEN, TODAY_IST.minusDays(30), TODAY_IST.plusDays(6)), Instant.now(CLOCK));
        Invoice justEnough = Invoice.createDraft(UUID.randomUUID(), UUID.randomUUID(),
                details(BigDecimal.TEN, TODAY_IST.minusDays(30), TODAY_IST.plusDays(7)), Instant.now(CLOCK));

        assertRule(() -> policy.checkTimeToMaturity(tooSoon), "DUE_DATE_TOO_SOON");
        assertThatCode(() -> policy.checkTimeToMaturity(justEnough)).doesNotThrowAnyException();
    }
}
