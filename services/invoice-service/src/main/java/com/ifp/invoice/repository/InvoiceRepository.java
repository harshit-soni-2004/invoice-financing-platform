package com.ifp.invoice.repository;

import com.ifp.invoice.domain.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    Optional<Invoice> findByIdAndSellerId(UUID id, UUID sellerId);

    boolean existsBySellerIdAndInvoiceNumber(UUID sellerId, String invoiceNumber);
}
