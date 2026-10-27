package com.ifp.invoice.service;

import com.ifp.invoice.domain.Invoice;
import com.ifp.invoice.domain.InvoiceDetails;
import com.ifp.invoice.domain.InvoiceNotEditableException;
import com.ifp.invoice.domain.InvoicePolicy;
import com.ifp.invoice.domain.InvoiceStatus;
import com.ifp.invoice.domain.InvoiceStatusHistory;
import com.ifp.invoice.repository.InvoiceRepository;
import com.ifp.invoice.repository.InvoiceSpecifications;
import com.ifp.invoice.repository.InvoiceStatusHistoryRepository;
import com.ifp.invoice.security.Actor;
import com.ifp.invoice.security.Role;
import com.ifp.messaging.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Application service for invoices. Every write runs in one transaction that covers the invoice row and its
 * history row and its outbox event. Role checks are repeated here as defense in depth; ownership
 * is enforced by loading seller-owned invoices with the caller's ID.
 */
@Service
@Transactional
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
    private static final String UNIQUE_SELLER_NUMBER = "uk_invoices_seller_number";

    static final String SYSTEM_ROLE = "SYSTEM";
    /** Recorded as the history actor when a financing event carries no actor. */
    static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final InvoiceRepository invoices;
    private final InvoiceStatusHistoryRepository history;
    private final InvoicePolicy policy;
    private final Clock clock;
    private final OutboxWriter outbox;

    public InvoiceService(InvoiceRepository invoices, InvoiceStatusHistoryRepository history,
                          InvoicePolicy policy, Clock clock, OutboxWriter outbox) {
        this.invoices = invoices;
        this.history = history;
        this.policy = policy;
        this.clock = clock;
        this.outbox = outbox;
    }

    public Invoice create(Actor actor, InvoiceDetails details) {
        require(actor, Role.SELLER);
        policy.checkDetails(details);
        String number = details.invoiceNumber().strip();
        if (invoices.existsBySellerIdAndInvoiceNumber(actor.userId(), number)) {
            throw new DuplicateInvoiceNumberException(number);
        }
        Instant now = clock.instant();
        Invoice invoice = Invoice.createDraft(UUID.randomUUID(), actor.userId(), details, now);
        flushTranslatingDuplicates(invoice);
        record(invoice, null, actor, null, now);
        publish(invoice, null, actor.userId());
        log.info("Invoice {} created as DRAFT", invoice.getId());
        return invoice;
    }

    public Invoice update(Actor actor, UUID id, ExpectedVersion expected, InvoiceDetails details) {
        require(actor, Role.SELLER);
        Invoice invoice = loadOwned(actor, id);
        expected.check(invoice.getVersion());
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new InvoiceNotEditableException(invoice.getStatus());
        }
        policy.checkDetails(details);
        String number = details.invoiceNumber().strip();
        if (!number.equalsIgnoreCase(invoice.getInvoiceNumber())
                && invoices.existsBySellerIdAndInvoiceNumber(actor.userId(), number)) {
            throw new DuplicateInvoiceNumberException(number);
        }
        invoice.updateDetails(details, clock.instant());
        flushTranslatingDuplicates(invoice);
        log.info("Invoice {} details updated", invoice.getId());
        return invoice;
    }

    @Transactional(readOnly = true)
    public Invoice get(Actor actor, UUID id) {
        return loadVisible(actor, id);
    }

    @Transactional(readOnly = true)
    public Page<Invoice> list(Actor actor, UUID sellerIdFilter, InvoiceStatus status, Pageable pageable) {
        UUID sellerScope;
        if (actor.isAdmin()) {
            sellerScope = sellerIdFilter;
        } else if (actor.has(Role.SELLER)) {
            // Sellers are always scoped to themselves, whatever filter they send.
            sellerScope = actor.userId();
        } else {
            throw new AccessDeniedException("Not permitted to list invoices");
        }
        return invoices.findAll(InvoiceSpecifications.matching(sellerScope, status), pageable);
    }

    @Transactional(readOnly = true)
    public List<InvoiceStatusHistory> history(Actor actor, UUID id) {
        Invoice invoice = loadVisible(actor, id);
        return history.findByInvoiceIdOrderByOccurredAtAscIdAsc(invoice.getId());
    }

    public Invoice submit(Actor actor, UUID id, ExpectedVersion expected) {
        require(actor, Role.SELLER);
        Invoice invoice = loadOwned(actor, id);
        return transition(invoice, expected, actor, now -> {
            invoice.ensureCanTransitionTo(InvoiceStatus.SUBMITTED);
            policy.checkTimeToMaturity(invoice);
            invoice.submit(now);
        });
    }

    public Invoice cancel(Actor actor, UUID id, ExpectedVersion expected, String reason) {
        require(actor, Role.SELLER);
        Invoice invoice = loadOwned(actor, id);
        return transition(invoice, expected, actor, now -> invoice.cancel(reason, now));
    }

    public Invoice listForFinancing(Actor actor, UUID id, ExpectedVersion expected) {
        require(actor, Role.SELLER);
        Invoice invoice = loadOwned(actor, id);
        return transition(invoice, expected, actor, now -> {
            invoice.ensureCanTransitionTo(InvoiceStatus.FINANCING_AVAILABLE);
            policy.checkTimeToMaturity(invoice);
            invoice.listForFinancing(now);
        });
    }

    public Invoice confirmBuyer(Actor actor, UUID id, ExpectedVersion expected) {
        require(actor, Role.ADMIN);
        Invoice invoice = loadAny(id);
        return transition(invoice, expected, actor, now -> invoice.confirmBuyer(actor.userId(), now));
    }

    public Invoice verify(Actor actor, UUID id, ExpectedVersion expected) {
        require(actor, Role.ADMIN);
        Invoice invoice = loadAny(id);
        return transition(invoice, expected, actor, now -> invoice.verify(actor.userId(), now));
    }

    public Invoice reject(Actor actor, UUID id, ExpectedVersion expected, String reason) {
        require(actor, Role.ADMIN);
        Invoice invoice = loadAny(id);
        return transition(invoice, expected, actor, now -> invoice.reject(reason, now));
    }

    private Invoice transition(Invoice invoice, ExpectedVersion expected, Actor actor, Consumer<Instant> change) {
        expected.check(invoice.getVersion());
        InvoiceStatus from = invoice.getStatus();
        Instant now = clock.instant();
        change.accept(now);
        // Flush the versioned UPDATE before inserting history: the UPDATE takes the invoice row's exclusive lock
        // first, so a concurrent writer fails on the version check instead of deadlocking on the history FK lock.
        invoices.saveAndFlush(invoice);
        record(invoice, from, actor, invoice.getStatusReason(), now);
        publish(invoice, from, actor.userId());
        log.info("Invoice {} moved {} -> {}", invoice.getId(), from, invoice.getStatus());
        return invoice;
    }

    /**
     * Applies a financing outcome reported by financing-service. Redelivered or already-applied outcomes are
     * no-ops. A settlement that overtakes its disbursement event applies both steps, so history stays complete.
     *
     * @param target FINANCED or SETTLED
     * @return whether the invoice changed
     */
    public boolean applyFinancingOutcome(UUID invoiceId, InvoiceStatus target, UUID eventActorId) {
        if (target != InvoiceStatus.FINANCED && target != InvoiceStatus.SETTLED) {
            throw new IllegalArgumentException("Unsupported financing outcome " + target);
        }
        Invoice invoice = loadAny(invoiceId);
        InvoiceStatus current = invoice.getStatus();
        if (current == target || (target == InvoiceStatus.FINANCED && current == InvoiceStatus.SETTLED)) {
            log.info("Invoice {} already {}; financing outcome {} ignored", invoiceId, current, target);
            return false;
        }
        UUID actorId = eventActorId != null ? eventActorId : SYSTEM_ACTOR;
        if (current != InvoiceStatus.FINANCED) {
            // Throws InvalidStatusTransitionException unless the invoice is FINANCING_AVAILABLE.
            systemTransition(invoice, actorId, invoice::markFinanced);
        }
        if (target == InvoiceStatus.SETTLED) {
            systemTransition(invoice, actorId, invoice::markSettled);
        }
        return true;
    }

    private void systemTransition(Invoice invoice, UUID actorId, Consumer<Instant> change) {
        InvoiceStatus from = invoice.getStatus();
        Instant now = clock.instant();
        change.accept(now);
        invoices.saveAndFlush(invoice);
        history.save(new InvoiceStatusHistory(invoice.getId(), from, invoice.getStatus(), actorId, SYSTEM_ROLE, null, now));
        publish(invoice, from, actorId);
        log.info("Invoice {} moved {} -> {} by financing event", invoice.getId(), from, invoice.getStatus());
    }

    private void record(Invoice invoice, InvoiceStatus from, Actor actor, String reason, Instant now) {
        history.save(new InvoiceStatusHistory(invoice.getId(), from, invoice.getStatus(), actor.userId(),
                actor.primaryRole(), reason, now));
    }

    private void publish(Invoice invoice, InvoiceStatus from, UUID actorId) {
        outbox.append(InvoiceEvents.routingKey(invoice.getStatus()), InvoiceEvents.eventType(invoice.getStatus()),
                InvoiceEvents.AGGREGATE, invoice.getId(), actorId, InvoiceEvents.InvoiceChanged.of(invoice, from));
    }

    private void flushTranslatingDuplicates(Invoice invoice) {
        try {
            invoices.saveAndFlush(invoice);
        } catch (DataIntegrityViolationException e) {
            // The unique constraint is the real guarantee; the exists-check above only gives a friendlier fast path.
            String cause = e.getMostSpecificCause().getMessage();
            if (cause != null && cause.contains(UNIQUE_SELLER_NUMBER)) {
                throw new DuplicateInvoiceNumberException(invoice.getInvoiceNumber());
            }
            throw e;
        }
    }

    private Invoice loadOwned(Actor actor, UUID id) {
        return invoices.findByIdAndSellerId(id, actor.userId()).orElseThrow(() -> new InvoiceNotFoundException(id));
    }

    private Invoice loadAny(UUID id) {
        return invoices.findById(id).orElseThrow(() -> new InvoiceNotFoundException(id));
    }

    private Invoice loadVisible(Actor actor, UUID id) {
        if (actor.isAdmin()) {
            return loadAny(id);
        }
        if (actor.has(Role.SELLER)) {
            return loadOwned(actor, id);
        }
        throw new AccessDeniedException("Not permitted to view invoices");
    }

    private static void require(Actor actor, Role role) {
        if (!actor.has(role)) {
            throw new AccessDeniedException("Requires role " + role);
        }
    }
}
