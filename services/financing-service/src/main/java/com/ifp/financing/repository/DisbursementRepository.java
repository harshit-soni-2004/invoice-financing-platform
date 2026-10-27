package com.ifp.financing.repository;

import com.ifp.financing.domain.Disbursement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DisbursementRepository extends JpaRepository<Disbursement, UUID> {

    Optional<Disbursement> findByAgreementId(UUID agreementId);

    long countByAgreementId(UUID agreementId);
}
