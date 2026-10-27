package com.ifp.financing.service;

import com.ifp.financing.config.FinancingProperties;
import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.domain.BusinessRuleViolationException;
import com.ifp.financing.domain.ConflictException;
import com.ifp.financing.domain.FinanceableInvoice;
import com.ifp.financing.domain.FinancingAgreement;
import com.ifp.financing.domain.FinancingOffer;
import com.ifp.financing.domain.InvalidStatusTransitionException;
import com.ifp.financing.domain.InvoiceState;
import com.ifp.financing.domain.OfferStatus;
import com.ifp.financing.domain.OfferTerms;
import com.ifp.financing.domain.ResourceNotFoundException;
import com.ifp.financing.domain.StatusHistoryEntry;
import com.ifp.financing.repository.FinanceableInvoiceRepository;
import com.ifp.financing.repository.FinancingAgreementRepository;
import com.ifp.financing.repository.FinancingOfferRepository;
import com.ifp.financing.repository.FinancingSpecifications;
import com.ifp.financing.repository.StatusHistoryRepository;
import com.ifp.financing.security.Actor;
import com.ifp.financing.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Offers and their acceptance. Multiple financiers may compete: each may hold one open offer per invoice. When the
 * seller accepts one, all other open offers on that invoice are CLOSED and a single agreement is created; a unique
 * constraint on live agreements per invoice makes double financing impossible even under concurrency.
 */
@Service
@Transactional
public class OfferService {

    public static final Set<AgreementStatus> LIVE_AGREEMENT_STATUSES = EnumSet.of(AgreementStatus.PENDING_APPROVAL,
            AgreementStatus.APPROVED, AgreementStatus.DISBURSED, AgreementStatus.REPAYMENT_IN_PROGRESS, AgreementStatus.SETTLED);
    static final String ANOTHER_OFFER_ACCEPTED = "Another offer for this invoice was accepted";
    private static final String UNIQUE_OPEN_OFFER = "uk_financing_offers_open_per_financier";
    private static final String UNIQUE_LIVE_AGREEMENT = "uk_financing_agreements_live_invoice";
    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    private final FinanceableInvoiceRepository invoices;
    private final FinancingOfferRepository offers;
    private final FinancingAgreementRepository agreements;
    private final StatusHistoryRepository historyRepository;
    private final StatusHistoryRecorder history;
    private final FinancingProperties properties;
    private final Clock clock;

    public OfferService(FinanceableInvoiceRepository invoices, FinancingOfferRepository offers,
                        FinancingAgreementRepository agreements, StatusHistoryRepository historyRepository,
                        StatusHistoryRecorder history, FinancingProperties properties, Clock clock) {
        this.invoices = invoices;
        this.offers = offers;
        this.agreements = agreements;
        this.historyRepository = historyRepository;
        this.history = history;
        this.properties = properties;
        this.clock = clock;
    }

    // ---- Opportunities ----

    @Transactional(readOnly = true)
    public Page<FinanceableInvoice> opportunities(Actor actor, Pageable pageable) {
        requireFinancierOrAdmin(actor);
        return invoices.findOpenForOffers(InvoiceState.FINANCING_AVAILABLE,
                today().plusDays(properties.minDaysToMaturity()), LIVE_AGREEMENT_STATUSES, pageable);
    }

    @Transactional(readOnly = true)
    public FinanceableInvoice opportunity(Actor actor, UUID invoiceId) {
        requireFinancierOrAdmin(actor);
        FinanceableInvoice invoice = invoices.findById(invoiceId).orElseThrow(() -> ResourceNotFoundException.invoice(invoiceId));
        if (!isOpenForOffers(invoice)) {
            throw ResourceNotFoundException.invoice(invoiceId);
        }
        return invoice;
    }

    /** Largest advance an offer on this invoice may carry: invoice amount x max advance ratio, rounded down. */
    public BigDecimal maxAdvance(FinanceableInvoice invoice) {
        return invoice.getAmount().multiply(properties.maxAdvanceRatio()).setScale(2, RoundingMode.DOWN);
    }

    // ---- Offers ----

    public FinancingOffer create(Actor actor, UUID invoiceId, OfferTerms terms, Instant expiresAt) {
        actor.require(Role.FINANCIER);
        Instant now = clock.instant();
        FinanceableInvoice invoice = invoices.findForUpdate(invoiceId).orElseThrow(() -> ResourceNotFoundException.invoice(invoiceId));
        if (!invoice.isListedForFinancing()) {
            throw ResourceNotFoundException.invoice(invoiceId);
        }
        if (!invoice.isWithinMaturityWindow(today(), properties.minDaysToMaturity())) {
            throw new BusinessRuleViolationException("INVOICE_NOT_ELIGIBLE",
                    "The invoice is too close to its due date to be financed");
        }
        if (agreements.existsByInvoiceIdAndStatusIn(invoiceId, LIVE_AGREEMENT_STATUSES)) {
            throw new ConflictException("INVOICE_ALREADY_FINANCED", "The invoice already has a financing agreement");
        }
        checkTerms(invoice, terms);
        checkExpiry(invoice, expiresAt, now);
        if (offers.existsByInvoiceIdAndFinancierIdAndStatus(invoiceId, actor.userId(), OfferStatus.OFFERED)) {
            throw duplicateOpenOffer();
        }
        FinancingOffer offer = FinancingOffer.create(UUID.randomUUID(), invoice, actor.userId(), terms, expiresAt, now);
        try {
            offers.saveAndFlush(offer);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, UNIQUE_OPEN_OFFER)) {
                throw duplicateOpenOffer();
            }
            throw e;
        }
        history.record(StatusHistoryEntry.OFFER, offer.getId(), null, OfferStatus.OFFERED, actor, null, now);
        log.info("Offer {} created for invoice {}", offer.getId(), invoiceId);
        return offer;
    }

    @Transactional(readOnly = true)
    public Page<FinancingOffer> list(Actor actor, UUID invoiceId, OfferStatus status, Pageable pageable) {
        if (actor.isAdmin()) {
            return offers.findAll(FinancingSpecifications.offers(null, null, invoiceId, status), pageable);
        }
        if (actor.has(Role.FINANCIER)) {
            return offers.findAll(FinancingSpecifications.offers(null, actor.userId(), invoiceId, status), pageable);
        }
        if (actor.has(Role.SELLER)) {
            return offers.findAll(FinancingSpecifications.offers(actor.userId(), null, invoiceId, status), pageable);
        }
        throw new AccessDeniedException("Not permitted to list offers");
    }

    @Transactional(readOnly = true)
    public FinancingOffer get(Actor actor, UUID offerId) {
        return loadVisible(actor, offerId);
    }

    @Transactional(readOnly = true)
    public List<StatusHistoryEntry> history(Actor actor, UUID offerId) {
        FinancingOffer offer = loadVisible(actor, offerId);
        return historyRepository.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc(StatusHistoryEntry.OFFER, offer.getId());
    }

    public FinancingOffer withdraw(Actor actor, UUID offerId, String reason) {
        actor.require(Role.FINANCIER);
        FinancingOffer offer = offers.findByIdAndFinancierId(offerId, actor.userId())
                .orElseThrow(() -> ResourceNotFoundException.offer(offerId));
        Instant now = clock.instant();
        offer.withdraw(reason, now);
        offers.saveAndFlush(offer);
        history.record(StatusHistoryEntry.OFFER, offer.getId(), OfferStatus.OFFERED, OfferStatus.WITHDRAWN, actor, offer.getStatusReason(), now);
        return offer;
    }

    public FinancingOffer reject(Actor actor, UUID offerId, String reason) {
        actor.require(Role.SELLER);
        FinancingOffer offer = loadForSeller(actor, offerId);
        Instant now = clock.instant();
        ensureOpenAndUnexpired(offer, now);
        offer.reject(reason, now);
        offers.saveAndFlush(offer);
        history.record(StatusHistoryEntry.OFFER, offer.getId(), OfferStatus.OFFERED, OfferStatus.REJECTED, actor, offer.getStatusReason(), now);
        return offer;
    }

    /** Accepts the offer, creates the agreement (PENDING_APPROVAL) and closes competing offers, atomically. */
    public FinancingAgreement accept(Actor actor, UUID offerId) {
        actor.require(Role.SELLER);
        FinancingOffer offer = loadForSeller(actor, offerId);
        Instant now = clock.instant();
        if (offer.getStatus() != OfferStatus.OFFERED) {
            throw new InvalidStatusTransitionException("offer", offer.getStatus(), OfferStatus.ACCEPTED);
        }
        ensureOpenAndUnexpired(offer, now);
        FinanceableInvoice invoice = invoices.findForUpdate(offer.getInvoiceId())
                .orElseThrow(() -> ResourceNotFoundException.invoice(offer.getInvoiceId()));
        if (!invoice.isListedForFinancing() || !invoice.isWithinMaturityWindow(today(), properties.minDaysToMaturity())) {
            throw new BusinessRuleViolationException("INVOICE_NOT_ELIGIBLE", "The invoice can no longer be financed");
        }
        if (agreements.existsByInvoiceIdAndStatusIn(invoice.getInvoiceId(), LIVE_AGREEMENT_STATUSES)) {
            throw alreadyFinanced();
        }
        offer.accept(now);
        offers.saveAndFlush(offer);
        FinancingAgreement agreement = FinancingAgreement.fromAcceptedOffer(UUID.randomUUID(), offer, invoice, now);
        try {
            agreements.saveAndFlush(agreement);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, UNIQUE_LIVE_AGREEMENT)) {
                throw alreadyFinanced();
            }
            throw e;
        }
        history.record(StatusHistoryEntry.OFFER, offer.getId(), OfferStatus.OFFERED, OfferStatus.ACCEPTED, actor, null, now);
        history.record(StatusHistoryEntry.AGREEMENT, agreement.getId(), null, AgreementStatus.PENDING_APPROVAL, actor, null, now);
        for (FinancingOffer competing : offers.findByInvoiceIdAndStatus(invoice.getInvoiceId(), OfferStatus.OFFERED)) {
            competing.close(ANOTHER_OFFER_ACCEPTED, now);
            history.recordSystem(StatusHistoryEntry.OFFER, competing.getId(), OfferStatus.OFFERED, OfferStatus.CLOSED,
                    ANOTHER_OFFER_ACCEPTED, now);
        }
        offers.flush();
        log.info("Offer {} accepted; agreement {} created", offer.getId(), agreement.getId());
        return agreement;
    }

    /** Marks open offers whose validity has ended as EXPIRED. Returns how many were expired. */
    public int expireDueOffers(int batchSize) {
        Instant now = clock.instant();
        List<FinancingOffer> due = offers.findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                OfferStatus.OFFERED, now, PageRequest.of(0, batchSize));
        for (FinancingOffer offer : due) {
            offer.expire(now);
            history.recordSystem(StatusHistoryEntry.OFFER, offer.getId(), OfferStatus.OFFERED, OfferStatus.EXPIRED,
                    offer.getStatusReason(), now);
        }
        offers.flush();
        return due.size();
    }

    // ---- Rules ----

    private void checkTerms(FinanceableInvoice invoice, OfferTerms terms) {
        if (terms.advanceAmount().signum() <= 0) {
            throw new BusinessRuleViolationException("ADVANCE_NOT_POSITIVE", "advanceAmount must be positive");
        }
        BigDecimal cap = maxAdvance(invoice);
        if (terms.advanceAmount().compareTo(cap) > 0) {
            throw new BusinessRuleViolationException("ADVANCE_TOO_HIGH",
                    "advanceAmount may be at most " + cap.toPlainString() + " for this invoice");
        }
        if (terms.annualRate().signum() <= 0 || terms.annualRate().compareTo(properties.maxAnnualRate()) > 0) {
            throw new BusinessRuleViolationException("RATE_OUT_OF_RANGE",
                    "annualRate must be greater than 0 and at most " + properties.maxAnnualRate().toPlainString());
        }
        if (terms.processingFee().signum() < 0 || terms.processingFee().compareTo(terms.advanceAmount()) >= 0) {
            throw new BusinessRuleViolationException("PROCESSING_FEE_INVALID",
                    "processingFee must be zero or more and less than the advance");
        }
        if (terms.installmentCount() < 1 || terms.installmentCount() > properties.maxInstallments()) {
            throw new BusinessRuleViolationException("INSTALLMENTS_OUT_OF_RANGE",
                    "installmentCount must be between 1 and " + properties.maxInstallments());
        }
        if (ChronoUnit.DAYS.between(today(), invoice.getDueDate()) < terms.installmentCount()) {
            throw new BusinessRuleViolationException("MATURITY_TOO_SOON",
                    "Not enough days to maturity for " + terms.installmentCount() + " installment(s)");
        }
    }

    private void checkExpiry(FinanceableInvoice invoice, Instant expiresAt, Instant now) {
        Instant maturity = invoice.getDueDate().atStartOfDay(properties.businessZone()).toInstant();
        if (expiresAt == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(properties.maxOfferValidity()))
                || !expiresAt.isBefore(maturity)) {
            throw new BusinessRuleViolationException("EXPIRY_INVALID", "expiresAt must be in the future, within "
                    + properties.maxOfferValidity().toDays() + " days, and before the invoice due date");
        }
    }

    private boolean isOpenForOffers(FinanceableInvoice invoice) {
        return invoice.isListedForFinancing()
                && invoice.isWithinMaturityWindow(today(), properties.minDaysToMaturity())
                && !agreements.existsByInvoiceIdAndStatusIn(invoice.getInvoiceId(), LIVE_AGREEMENT_STATUSES);
    }

    private static void ensureOpenAndUnexpired(FinancingOffer offer, Instant now) {
        if (offer.getStatus() == OfferStatus.OFFERED && offer.isExpiredAt(now)) {
            throw new ConflictException("OFFER_EXPIRED", "The offer has expired");
        }
    }

    private FinancingOffer loadForSeller(Actor actor, UUID offerId) {
        return offers.findByIdAndSellerId(offerId, actor.userId()).orElseThrow(() -> ResourceNotFoundException.offer(offerId));
    }

    private FinancingOffer loadVisible(Actor actor, UUID offerId) {
        if (actor.isAdmin()) {
            return offers.findById(offerId).orElseThrow(() -> ResourceNotFoundException.offer(offerId));
        }
        if (actor.has(Role.FINANCIER)) {
            return offers.findByIdAndFinancierId(offerId, actor.userId()).orElseThrow(() -> ResourceNotFoundException.offer(offerId));
        }
        if (actor.has(Role.SELLER)) {
            return loadForSeller(actor, offerId);
        }
        throw new AccessDeniedException("Not permitted to view offers");
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(properties.businessZone()));
    }

    private static void requireFinancierOrAdmin(Actor actor) {
        if (!actor.has(Role.FINANCIER) && !actor.isAdmin()) {
            throw new AccessDeniedException("Requires role FINANCIER");
        }
    }

    private static ConflictException duplicateOpenOffer() {
        return new ConflictException("DUPLICATE_OPEN_OFFER", "You already have an open offer for this invoice");
    }

    private static ConflictException alreadyFinanced() {
        return new ConflictException("INVOICE_ALREADY_FINANCED", "The invoice already has a financing agreement");
    }

    static boolean violates(DataIntegrityViolationException e, String constraint) {
        String cause = e.getMostSpecificCause().getMessage();
        return cause != null && cause.contains(constraint);
    }
}
