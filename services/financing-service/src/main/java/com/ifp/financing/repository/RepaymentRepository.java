package com.ifp.financing.repository;

import com.ifp.financing.domain.Repayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RepaymentRepository extends JpaRepository<Repayment, UUID> {

    List<Repayment> findByAgreementIdOrderByReceivedAtAsc(UUID agreementId);

    long countByAgreementId(UUID agreementId);
}
