package com.ifp.financing.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;
import java.util.regex.Pattern;

/** Paging, whitelisted sorting and Idempotency-Key validation shared by the controllers. */
final class RequestSupport {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    static final Map<String, String> OPPORTUNITY_SORT = Map.of("dueDate", "dueDate", "amount", "amount",
            "invoiceNumber", "invoiceNumber");
    static final Map<String, String> OFFER_SORT = Map.of("createdAt", "createdAt", "expiresAt", "expiresAt",
            "advanceAmount", "advanceAmount");
    static final Map<String, String> AGREEMENT_SORT = Map.of("createdAt", "createdAt", "maturityDate", "maturityDate",
            "advanceAmount", "advanceAmount");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private RequestSupport() {
    }

    static Pageable page(int page, int size, String sort, Map<String, String> fields, String idProperty) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidRequestException("INVALID_QUERY_PARAMETER", "page must be >= 0 and size between 1 and 100");
        }
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || !fields.containsKey(parts[0].strip())) {
            throw new InvalidRequestException("INVALID_QUERY_PARAMETER",
                    "sort must be one of " + fields.keySet() + " optionally followed by ,asc or ,desc");
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length == 2) {
            direction = Sort.Direction.fromOptionalString(parts[1].strip())
                    .orElseThrow(() -> new InvalidRequestException("INVALID_QUERY_PARAMETER", "sort direction must be asc or desc"));
        }
        // Tie-breaker keeps page boundaries stable when many rows share the sort value.
        return PageRequest.of(page, size, Sort.by(direction, fields.get(parts[0].strip())).and(Sort.by(Sort.Direction.ASC, idProperty)));
    }

    static String idempotencyKey(String header) {
        if (header == null || header.isBlank()) {
            throw new InvalidRequestException("IDEMPOTENCY_KEY_REQUIRED", "The Idempotency-Key header is required");
        }
        if (!KEY.matcher(header.strip()).matches()) {
            throw new InvalidRequestException("IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must be 8-100 characters of letters, digits, '-' or '_'");
        }
        return header.strip();
    }
}
