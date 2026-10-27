package com.ifp.invoice.web.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static InvoiceRequest valid() {
        return new InvoiceRequest("INV/2026-001", "Acme Buyers", "27AAPFU0939F1ZV", "Steel",
                new BigDecimal("500000.00"), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-30"));
    }

    private static Set<String> invalidFields(InvoiceRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void validRequestPasses() {
        assertThat(invalidFields(valid())).isEmpty();
    }

    @Test
    void optionalFieldsMayBeNull() {
        assertThat(invalidFields(new InvoiceRequest("INV-1", "Buyer", null, null, BigDecimal.ONE,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02")))).isEmpty();
    }

    @Test
    void requiredFieldsMustBePresent() {
        assertThat(invalidFields(new InvoiceRequest(" ", "", null, null, null, null, null)))
                .containsExactlyInAnyOrder("invoiceNumber", "buyerName", "amount", "issueDate", "dueDate");
    }

    @Test
    void invoiceNumberCharactersAndLengthAreRestricted() {
        assertThat(invalidFields(withNumber("INV 001"))).containsExactly("invoiceNumber");
        assertThat(invalidFields(withNumber("-INV"))).containsExactly("invoiceNumber");
        assertThat(invalidFields(withNumber("<script>"))).containsExactly("invoiceNumber");
        assertThat(invalidFields(withNumber("A".repeat(51)))).containsExactly("invoiceNumber");
    }

    @Test
    void amountMustBePositiveWithAtMostTwoDecimals() {
        assertThat(invalidFields(withAmount("0.00"))).containsExactly("amount");
        assertThat(invalidFields(withAmount("-5"))).containsExactly("amount");
        assertThat(invalidFields(withAmount("10.005"))).containsExactly("amount");
        assertThat(invalidFields(withAmount("0.01"))).isEmpty();
    }

    @Test
    void gstinMustMatchFormat() {
        InvoiceRequest base = valid();
        InvoiceRequest bad = new InvoiceRequest(base.invoiceNumber(), base.buyerName(), "27aapfu0939f1zv",
                base.description(), base.amount(), base.issueDate(), base.dueDate());
        assertThat(invalidFields(bad)).containsExactly("buyerGstin");
    }

    private static InvoiceRequest withNumber(String number) {
        InvoiceRequest b = valid();
        return new InvoiceRequest(number, b.buyerName(), b.buyerGstin(), b.description(), b.amount(), b.issueDate(), b.dueDate());
    }

    private static InvoiceRequest withAmount(String amount) {
        InvoiceRequest b = valid();
        return new InvoiceRequest(b.invoiceNumber(), b.buyerName(), b.buyerGstin(), b.description(), new BigDecimal(amount),
                b.issueDate(), b.dueDate());
    }
}
