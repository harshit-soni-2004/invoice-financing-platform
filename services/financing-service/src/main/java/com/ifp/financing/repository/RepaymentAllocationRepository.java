package com.ifp.financing.repository;

import com.ifp.financing.domain.RepaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RepaymentAllocationRepository extends JpaRepository<RepaymentAllocation, Long> {

    List<RepaymentAllocation> findByRepaymentId(UUID repaymentId);
}
