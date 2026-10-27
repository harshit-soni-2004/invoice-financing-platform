package com.ifp.invoice.persistence;

import com.ifp.invoice.domain.Invoice;
import com.ifp.invoice.domain.InvoiceDetails;
import com.ifp.invoice.domain.InvoiceStatus;
import com.ifp.invoice.repository.InvoiceRepository;
import com.ifp.invoice.repository.InvoiceSpecifications;
import com.ifp.invoice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private InvoiceRepository repository;

    @Autowired
    private TransactionTemplate tx;

    private static InvoiceDetails details(String number, String amount) {
        return new InvoiceDetails(number, "Buyer Co", null, null, new BigDecimal(amount),
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-01"));
    }

    private Invoice newInvoice(UUID seller, String number) {
        return Invoice.createDraft(UUID.randomUUID(), seller, details(number, "500000.00"), Instant.now());
    }

    @Test
    void persistsMoneyUuidsDatesAndUtcTimestampsExactly() {
        UUID seller = UUID.randomUUID();
        // Microsecond precision, as produced by the application Clock.
        Instant created = Instant.parse("2026-10-27T18:45:12.123456Z");
        Invoice invoice = Invoice.createDraft(UUID.randomUUID(), seller, details("EXACT-1", "489468.49"), created);
        repository.saveAndFlush(invoice);

        Invoice reloaded = tx.execute(s -> repository.findById(invoice.getId()).orElseThrow());

        assertThat(reloaded.getAmount()).isEqualTo(new BigDecimal("489468.49"));
        assertThat(reloaded.getSellerId()).isEqualTo(seller);
        assertThat(reloaded.getIssueDate()).isEqualTo(LocalDate.parse("2026-10-01"));
        // DATETIME(6) keeps microseconds; the instant must not shift by any time-zone offset.
        assertThat(reloaded.getCreatedAt()).isEqualTo(created);
        assertThat(reloaded.getVersion()).isZero();
    }

    @Test
    void invoiceNumberIsUniquePerSellerAndCaseInsensitive() {
        UUID seller = UUID.randomUUID();
        repository.saveAndFlush(newInvoice(seller, "INV-100"));

        assertThatThrownBy(() -> repository.saveAndFlush(newInvoice(seller, "inv-100")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_invoices_seller_number");

        // Another seller may reuse the same number.
        repository.saveAndFlush(newInvoice(UUID.randomUUID(), "INV-100"));
        assertThat(repository.existsBySellerIdAndInvoiceNumber(seller, "INV-100")).isTrue();
    }

    @Test
    void staleUpdateIsRejectedByOptimisticLocking() {
        Invoice saved = repository.saveAndFlush(newInvoice(UUID.randomUUID(), "LOCK-1"));

        Invoice copyA = tx.execute(s -> repository.findById(saved.getId()).orElseThrow());
        Invoice copyB = tx.execute(s -> repository.findById(saved.getId()).orElseThrow());

        copyA.submit(Instant.now());
        repository.saveAndFlush(copyA);

        copyB.cancel("stale", Instant.now());
        assertThatThrownBy(() -> repository.saveAndFlush(copyB)).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Invoice current = repository.findById(saved.getId()).orElseThrow();
        assertThat(current.getStatus()).isEqualTo(InvoiceStatus.SUBMITTED);
        assertThat(current.getVersion()).isEqualTo(1L);
    }

    @Test
    void filtersBySellerAndStatusWithPagination() {
        UUID seller = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            repository.saveAndFlush(newInvoice(seller, "PAGE-" + i));
        }
        Invoice submitted = newInvoice(seller, "PAGE-S");
        submitted.submit(Instant.now());
        repository.saveAndFlush(submitted);
        repository.saveAndFlush(newInvoice(UUID.randomUUID(), "PAGE-OTHER"));

        Page<Invoice> drafts = repository.findAll(InvoiceSpecifications.matching(seller, InvoiceStatus.DRAFT),
                PageRequest.of(0, 2, Sort.by("invoiceNumber")));
        assertThat(drafts.getTotalElements()).isEqualTo(3);
        assertThat(drafts.getContent()).extracting(Invoice::getInvoiceNumber).containsExactly("PAGE-0", "PAGE-1");

        assertThat(repository.findAll(InvoiceSpecifications.matching(seller, null))).hasSize(4);
    }
}
