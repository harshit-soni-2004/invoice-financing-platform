package com.ifp.financing.repository;

import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.domain.FinancingAgreement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancingAgreementRepository extends JpaRepository<FinancingAgreement, UUID>,
        JpaSpecificationExecutor<FinancingAgreement> {

    boolean existsByInvoiceIdAndStatusIn(UUID invoiceId, Collection<AgreementStatus> statuses);

    List<FinancingAgreement> findByInvoiceIdAndStatusIn(UUID invoiceId, Collection<AgreementStatus> statuses);

    /** Row lock for money movements, so concurrent disbursements and repayments on one agreement serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FinancingAgreement a where a.id = :id")
    Optional<FinancingAgreement> findForUpdate(@Param("id") UUID id);
}
