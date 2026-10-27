package com.ifp.financing.repository;

import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.domain.FinancingAgreement;
import com.ifp.financing.domain.FinancingOffer;
import com.ifp.financing.domain.OfferStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** List filters. Party scoping (seller / financier) is applied by the services from the caller's identity. */
public final class FinancingSpecifications {

    private FinancingSpecifications() {
    }

    public static Specification<FinancingOffer> offers(UUID sellerId, UUID financierId, UUID invoiceId, OfferStatus status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (sellerId != null) {
                predicates.add(cb.equal(root.get("sellerId"), sellerId));
            }
            if (financierId != null) {
                predicates.add(cb.equal(root.get("financierId"), financierId));
            }
            if (invoiceId != null) {
                predicates.add(cb.equal(root.get("invoiceId"), invoiceId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<FinancingAgreement> agreements(UUID sellerId, UUID financierId, AgreementStatus status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (sellerId != null) {
                predicates.add(cb.equal(root.get("sellerId"), sellerId));
            }
            if (financierId != null) {
                predicates.add(cb.equal(root.get("financierId"), financierId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
