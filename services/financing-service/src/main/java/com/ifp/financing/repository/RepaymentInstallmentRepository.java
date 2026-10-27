package com.ifp.financing.repository;

import com.ifp.financing.domain.RepaymentInstallment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RepaymentInstallmentRepository extends JpaRepository<RepaymentInstallment, UUID> {

    List<RepaymentInstallment> findByAgreementIdOrderBySequenceNoAsc(UUID agreementId);

    /**
     * Locking read: under REPEATABLE READ a plain read could return a snapshot taken before a concurrent repayment
     * committed, even though the agreement row lock has already been acquired.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from RepaymentInstallment i where i.agreementId = :agreementId order by i.sequenceNo asc")
    List<RepaymentInstallment> findForUpdate(@Param("agreementId") UUID agreementId);
}
