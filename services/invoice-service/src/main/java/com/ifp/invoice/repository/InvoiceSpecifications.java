package com.ifp.invoice.repository;

import com.ifp.invoice.domain.Invoice;
import com.ifp.invoice.domain.InvoiceStatus;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

public final class InvoiceSpecifications {

    private InvoiceSpecifications() {
    }

    /** Builds a filter; {@code null} arguments are ignored. */
    public static Specification<Invoice> matching(UUID sellerId, InvoiceStatus status) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (sellerId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("sellerId"), sellerId));
            }
            if (status != null) {
                predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            }
            return predicate;
        };
    }
}
