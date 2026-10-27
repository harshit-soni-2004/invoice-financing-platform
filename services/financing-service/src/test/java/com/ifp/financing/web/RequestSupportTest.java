package com.ifp.financing.web;

import com.ifp.financing.service.IdempotentExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestSupportTest {

    @Test
    void whitelistedSortGetsIdTieBreaker() {
        Pageable pageable = RequestSupport.page(1, 10, "expiresAt,asc", RequestSupport.OFFER_SORT, "id");
        assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("expiresAt"), Sort.Order.asc("id"));
        assertThat(pageable.getPageNumber()).isEqualTo(1);
    }

    @Test
    void directionDefaultsToDescending() {
        assertThat(RequestSupport.page(0, 10, "createdAt", RequestSupport.OFFER_SORT, "id").getSort().getOrderFor("createdAt"))
                .isEqualTo(Sort.Order.desc("createdAt"));
    }

    @Test
    void unknownSortFieldsAndBadPagingAreRejected() {
        assertThatThrownBy(() -> RequestSupport.page(0, 10, "sellerId", RequestSupport.OFFER_SORT, "id"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> RequestSupport.page(0, 10, "createdAt,sideways", RequestSupport.OFFER_SORT, "id"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> RequestSupport.page(0, 101, "createdAt", RequestSupport.OFFER_SORT, "id"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> RequestSupport.page(-1, 10, "createdAt", RequestSupport.OFFER_SORT, "id"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void idempotencyKeyFormatIsEnforced() {
        assertThat(RequestSupport.idempotencyKey("abc-123_XYZ")).isEqualTo("abc-123_XYZ");
        assertThatThrownBy(() -> RequestSupport.idempotencyKey(null)).isInstanceOf(InvalidRequestException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThatThrownBy(() -> RequestSupport.idempotencyKey("short")).isInstanceOf(InvalidRequestException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_INVALID");
        assertThatThrownBy(() -> RequestSupport.idempotencyKey("has spaces in it")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> RequestSupport.idempotencyKey("x".repeat(101))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void requestHashIsStableAndSeparatorSafe() {
        assertThat(IdempotentExecutor.hash("A", "B")).isEqualTo(IdempotentExecutor.hash("A", "B")).hasSize(64);
        assertThat(IdempotentExecutor.hash("AB", "C")).isNotEqualTo(IdempotentExecutor.hash("A", "BC"));
        assertThat(IdempotentExecutor.hash("REPAYMENT", "x", "10.00")).isNotEqualTo(IdempotentExecutor.hash("REPAYMENT", "x", "10.01"));
    }
}
