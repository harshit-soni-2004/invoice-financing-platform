package com.ifp.financing.service;

import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.domain.FinanceableInvoice;
import com.ifp.financing.domain.FinancingAgreement;
import com.ifp.financing.domain.FinancingOffer;
import com.ifp.financing.domain.InvoiceSnapshot;
import com.ifp.financing.domain.InvoiceState;
import com.ifp.financing.domain.OfferStatus;
import com.ifp.financing.domain.StatusHistoryEntry;
import com.ifp.financing.repository.FinanceableInvoiceRepository;
import com.ifp.financing.repository.FinancingAgreementRepository;
import com.ifp.financing.repository.FinancingOfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;

/**
 * The only way the invoice read model changes. Phase 4 calls it directly (tests, tooling); Phase 5 feeds it from
 * invoice-service events. Snapshots are ordered by invoice-service's row version, so duplicates and late arrivals
 * are ignored instead of rolling the projection back.
 */
@Service
@Transactional
public class InvoiceProjectionService {

    public enum Outcome { CREATED, UPDATED, IGNORED_STALE }

    static final String INVOICE_WITHDRAWN = "Invoice is no longer available for financing";
    private static final Logger log = LoggerFactory.getLogger(InvoiceProjectionService.class);

    private final FinanceableInvoiceRepository invoices;
    private final FinancingOfferRepository offers;
    private final FinancingAgreementRepository agreements;
    private final StatusHistoryRecorder history;
    private final Clock clock;

    public InvoiceProjectionService(FinanceableInvoiceRepository invoices, FinancingOfferRepository offers,
                                    FinancingAgreementRepository agreements, StatusHistoryRecorder history, Clock clock) {
        this.invoices = invoices;
        this.offers = offers;
        this.agreements = agreements;
        this.history = history;
        this.clock = clock;
    }

    public Outcome apply(InvoiceSnapshot snapshot) {
        Instant now = clock.instant();
        Optional<FinanceableInvoice> existing = invoices.findForUpdate(snapshot.invoiceId());
        if (existing.isEmpty()) {
            invoices.saveAndFlush(FinanceableInvoice.from(snapshot, now));
            log.info("Projection created for invoice {} (status {}, source version {})",
                    snapshot.invoiceId(), snapshot.status(), snapshot.sourceVersion());
            return Outcome.CREATED;
        }
        FinanceableInvoice invoice = existing.get();
        InvoiceState before = invoice.getInvoiceStatus();
        if (!invoice.apply(snapshot, now)) {
            log.info("Ignored stale snapshot for invoice {} (source version {} <= {})",
                    snapshot.invoiceId(), snapshot.sourceVersion(), invoice.getSourceVersion());
            return Outcome.IGNORED_STALE;
        }
        invoices.saveAndFlush(invoice);
        if (before == InvoiceState.FINANCING_AVAILABLE && !invoice.isListedForFinancing()) {
            withdrawFinancing(invoice, now);
        }
        log.info("Projection for invoice {} moved {} -> {}", invoice.getInvoiceId(), before, invoice.getInvoiceStatus());
        return Outcome.UPDATED;
    }

    /**
     * The invoice left the market for a reason other than our own financing (FINANCED / SETTLED are echoes of
     * financing events): open offers are closed and agreements not yet disbursed are cancelled.
     */
    private void withdrawFinancing(FinanceableInvoice invoice, Instant now) {
        if (invoice.getInvoiceStatus() == InvoiceState.FINANCED || invoice.getInvoiceStatus() == InvoiceState.SETTLED) {
            return;
        }
        for (FinancingOffer offer : offers.findByInvoiceIdAndStatus(invoice.getInvoiceId(), OfferStatus.OFFERED)) {
            offer.close(INVOICE_WITHDRAWN, now);
            history.recordSystem(StatusHistoryEntry.OFFER, offer.getId(), OfferStatus.OFFERED, OfferStatus.CLOSED, INVOICE_WITHDRAWN, now);
        }
        for (FinancingAgreement agreement : agreements.findByInvoiceIdAndStatusIn(invoice.getInvoiceId(),
                EnumSet.of(AgreementStatus.PENDING_APPROVAL, AgreementStatus.APPROVED))) {
            AgreementStatus from = agreement.getStatus();
            agreement.cancel(INVOICE_WITHDRAWN, now);
            history.recordSystem(StatusHistoryEntry.AGREEMENT, agreement.getId(), from, AgreementStatus.CANCELLED, INVOICE_WITHDRAWN, now);
        }
    }
}
