package com.ifp.invoice.service;

import com.ifp.invoice.domain.InvoiceStatus;
import com.ifp.invoice.repository.InvoiceRepository;
import com.ifp.invoice.repository.InvoiceStatusHistoryRepository;
import com.ifp.invoice.security.Actor;
import com.ifp.invoice.security.Role;
import com.ifp.invoice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRollbackIT extends AbstractIntegrationTest {

    @Autowired
    private InvoiceService service;
    @Autowired
    private InvoiceRepository invoices;
    @Autowired
    private InvoiceStatusHistoryRepository history;
    @Autowired
    private TransactionTemplate tx;

    @Test
    void failureLaterInTheSameTransactionRollsBackStatusAndHistory() throws Exception {
        UUID sellerId = UUID.randomUUID();
        UUID id = createInvoice(sellerId);
        Actor seller = new Actor(sellerId, Set.of(Role.SELLER));

        // Stands in for a later step in the same unit of work, such as the Phase 5 outbox insert.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.submit(seller, id, ExpectedVersion.ANY);
            assertThat(history.countByInvoiceId(id)).isEqualTo(2);
            throw new IllegalStateException("simulated failure after the status change");
        })).isInstanceOf(IllegalStateException.class);

        var reloaded = invoices.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(reloaded.getVersion()).isZero();
        assertThat(reloaded.getSubmittedAt()).isNull();
        assertThat(history.countByInvoiceId(id)).isEqualTo(1);
    }

    @Test
    void rejectedCreationLeavesNoRows() {
        UUID sellerId = UUID.randomUUID();
        Actor seller = new Actor(sellerId, Set.of(Role.SELLER));
        var invalid = new com.ifp.invoice.domain.InvoiceDetails("ROLLBACK-1", "Buyer", null, null,
                new java.math.BigDecimal("10.00"), TODAY.plusDays(5), TODAY.plusDays(30));

        assertThatThrownBy(() -> service.create(seller, invalid))
                .isInstanceOf(com.ifp.invoice.domain.BusinessRuleViolationException.class);
        assertThat(invoices.existsBySellerIdAndInvoiceNumber(sellerId, "ROLLBACK-1")).isFalse();
    }
}
