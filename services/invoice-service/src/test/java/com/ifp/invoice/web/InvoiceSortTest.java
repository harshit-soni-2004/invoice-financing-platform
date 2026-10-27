package com.ifp.invoice.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceSortTest {

    @Test
    void defaultsToDescendingWithIdTieBreaker() {
        Sort sort = InvoiceSort.parse("dueDate");
        assertThat(sort.getOrderFor("dueDate").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void acceptsExplicitDirection() {
        assertThat(InvoiceSort.parse("amount,asc").getOrderFor("amount").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sellerId", "status", "amount,sideways", "amount,asc,extra", "", "id;DROP TABLE invoices"})
    void rejectsAnythingOutsideWhitelist(String expression) {
        assertThatThrownBy(() -> InvoiceSort.parse(expression)).isInstanceOf(InvalidQueryParameterException.class);
    }
}
