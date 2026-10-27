package com.ifp.financing.repository;

import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.domain.FinanceableInvoice;
import com.ifp.financing.domain.InvoiceState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface FinanceableInvoiceRepository extends JpaRepository<FinanceableInvoice, UUID> {

    /** Invoices open for offers: listed, far enough from maturity, and without a live agreement. */
    @Query("""
            select i from FinanceableInvoice i
            where i.invoiceStatus = :status
              and i.dueDate >= :minDueDate
              and not exists (select a.id from FinancingAgreement a
                              where a.invoiceId = i.invoiceId and a.status in :liveStatuses)
            """)
    Page<FinanceableInvoice> findOpenForOffers(@Param("status") InvoiceState status,
                                               @Param("minDueDate") LocalDate minDueDate,
                                               @Param("liveStatuses") Collection<AgreementStatus> liveStatuses,
                                               Pageable pageable);

    /** Row lock that serializes offer creation and acceptance per invoice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from FinanceableInvoice i where i.invoiceId = :invoiceId")
    Optional<FinanceableInvoice> findForUpdate(@Param("invoiceId") UUID invoiceId);
}
