package com.ifp.common.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void propagatesValidIncomingCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "req-12345678");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(MDC.get(CorrelationId.MDC_KEY)));

        assertThat(seenInChain.get()).isEqualTo("req-12345678");
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("req-12345678");
    }

    @Test
    void generatesIdWhenHeaderMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> { });

        assertThat(UUID.fromString(response.getHeader(CorrelationId.HEADER))).isNotNull();
    }

    @Test
    void replacesMaliciousHeaderToPreventLogInjection() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "abc\r\nFAKE LOG LINE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(CorrelationId.HEADER)).doesNotContain("FAKE").matches("[0-9a-f-]{36}");
    }

    @Test
    void clearsMdcAfterRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> { });

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void rejectsTooShortOrTooLongValues() {
        assertThat(CorrelationId.isValid("short")).isFalse();
        assertThat(CorrelationId.isValid("a".repeat(65))).isFalse();
        assertThat(CorrelationId.isValid(null)).isFalse();
    }
}
