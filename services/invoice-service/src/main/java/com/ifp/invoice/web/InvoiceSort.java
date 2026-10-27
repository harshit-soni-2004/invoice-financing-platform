package com.ifp.invoice.web;

import org.springframework.data.domain.Sort;

import java.util.Map;

/** Whitelisted sort expressions ({@code field,asc|desc}); arbitrary entity paths are never accepted. */
final class InvoiceSort {

    private static final Map<String, String> FIELDS = Map.of(
            "createdAt", "createdAt",
            "dueDate", "dueDate",
            "amount", "amount",
            "invoiceNumber", "invoiceNumber");

    private InvoiceSort() {
    }

    static Sort parse(String expression) {
        String[] parts = expression.split(",", -1);
        if (parts.length > 2 || !FIELDS.containsKey(parts[0].strip())) {
            throw new InvalidQueryParameterException("sort must be one of " + FIELDS.keySet() + " optionally followed by ,asc or ,desc");
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length == 2) {
            direction = Sort.Direction.fromOptionalString(parts[1].strip())
                    .orElseThrow(() -> new InvalidQueryParameterException("sort direction must be asc or desc"));
        }
        // Tie-breaker keeps page boundaries stable when many rows share the sort value.
        return Sort.by(direction, FIELDS.get(parts[0].strip())).and(Sort.by(Sort.Direction.ASC, "id"));
    }
}
