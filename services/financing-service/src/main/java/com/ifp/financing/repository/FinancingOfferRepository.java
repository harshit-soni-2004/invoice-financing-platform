package com.ifp.financing.repository;

import com.ifp.financing.domain.FinancingOffer;
import com.ifp.financing.domain.OfferStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancingOfferRepository extends JpaRepository<FinancingOffer, UUID>, JpaSpecificationExecutor<FinancingOffer> {

    Optional<FinancingOffer> findByIdAndFinancierId(UUID id, UUID financierId);

    Optional<FinancingOffer> findByIdAndSellerId(UUID id, UUID sellerId);

    List<FinancingOffer> findByInvoiceIdAndStatus(UUID invoiceId, OfferStatus status);

    boolean existsByInvoiceIdAndFinancierIdAndStatus(UUID invoiceId, UUID financierId, OfferStatus status);

    List<FinancingOffer> findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(OfferStatus status, Instant now, Pageable limit);
}
