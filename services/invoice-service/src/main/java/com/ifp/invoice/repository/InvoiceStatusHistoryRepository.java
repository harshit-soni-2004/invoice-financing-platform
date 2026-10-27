package com.ifp.invoice.repository;

import com.ifp.invoice.domain.InvoiceStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvoiceStatusHistoryRepository extends JpaRepository<InvoiceStatusHistory, Long> {

    List<InvoiceStatusHistory> findByInvoiceIdOrderByOccurredAtAscIdAsc(UUID invoiceId);

    long countByInvoiceId(UUID invoiceId);
}
