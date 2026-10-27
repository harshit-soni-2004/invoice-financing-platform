package com.ifp.financing.service;

import com.ifp.financing.config.FinancingProperties;
import com.ifp.financing.domain.AgreementStatus;
import com.ifp.financing.domain.BusinessRuleViolationException;
import com.ifp.financing.domain.ConflictException;
import com.ifp.financing.domain.Disbursement;
import com.ifp.financing.domain.FinancingAgreement;
import com.ifp.financing.domain.Money;
import com.ifp.financing.domain.Repayment;
import com.ifp.financing.domain.RepaymentAllocation;
import com.ifp.financing.domain.RepaymentInstallment;
import com.ifp.financing.domain.RepaymentPlan;
import com.ifp.financing.domain.ResourceNotFoundException;
import com.ifp.financing.domain.StatusHistoryEntry;
import com.ifp.financing.repository.DisbursementRepository;
import com.ifp.financing.repository.FinancingAgreementRepository;
import com.ifp.financing.repository.FinancingSpecifications;
import com.ifp.financing.repository.RepaymentAllocationRepository;
import com.ifp.financing.repository.RepaymentInstallmentRepository;
import com.ifp.financing.repository.RepaymentRepository;
import com.ifp.financing.repository.StatusHistoryRepository;
import com.ifp.financing.security.Actor;
import com.ifp.financing.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Agreement lifecycle: financier approval or rejection, cancellation, simulated disbursement and repayments.
 * Disbursement and repayment are idempotent per Idempotency-Key and lock the agreement row, so concurrent requests
 * serialize; the unique constraint on disbursements.agreement_id is the final guard against paying out twice.
 */
@Service
@Transactional
public class AgreementService {

    public record AgreementDetails(FinancingAgreement agreement, BigDecimal overdueAmount, LocalDate nextDueDate) {
    }

    static final String OPERATION_DISBURSE = "DISBURSE";
    static final String OPERATION_REPAYMENT = "REPAYMENT";
    private static final Logger log = LoggerFactory.getLogger(AgreementService.class);

    private final FinancingAgreementRepository agreements;
    private final DisbursementRepository disbursements;
    private final RepaymentInstallmentRepository installments;
    private final RepaymentRepository repayments;
    private final RepaymentAllocationRepository allocations;
    private final StatusHistoryRepository historyRepository;
    private final StatusHistoryRecorder history;
    private final IdempotentExecutor idempotent;
    private final FinancingProperties properties;
    private final Clock clock;
    private final FinancingEvents events;

    public AgreementService(FinancingAgreementRepository agreements, DisbursementRepository disbursements,
                            RepaymentInstallmentRepository installments, RepaymentRepository repayments,
                            RepaymentAllocationRepository allocations, StatusHistoryRepository historyRepository,
                            StatusHistoryRecorder history, IdempotentExecutor idempotent,
                            FinancingProperties properties, Clock clock, FinancingEvents events) {
        this.events = events;
        this.agreements = agreements;
        this.disbursements = disbursements;
        this.installments = installments;
        this.repayments = repayments;
        this.allocations = allocations;
        this.historyRepository = historyRepository;
        this.history = history;
        this.idempotent = idempotent;
        this.properties = properties;
        this.clock = clock;
    }

    // ---- Queries ----

    @Transactional(readOnly = true)
    public Page<FinancingAgreement> list(Actor actor, AgreementStatus status, Pageable pageable) {
        if (actor.isAdmin()) {
            return agreements.findAll(FinancingSpecifications.agreements(null, null, status), pageable);
        }
        if (actor.has(Role.FINANCIER)) {
            return agreements.findAll(FinancingSpecifications.agreements(null, actor.userId(), status), pageable);
        }
        if (actor.has(Role.SELLER)) {
            return agreements.findAll(FinancingSpecifications.agreements(actor.userId(), null, status), pageable);
        }
        throw new AccessDeniedException("Not permitted to list agreements");
    }

    @Transactional(readOnly = true)
    public AgreementDetails get(Actor actor, UUID id) {
        FinancingAgreement agreement = loadVisible(actor, id);
        LocalDate today = today();
        List<RepaymentInstallment> schedule = installments.findByAgreementIdOrderBySequenceNoAsc(id);
        BigDecimal overdue = schedule.stream()
                .filter(i -> i.statusOn(today) == RepaymentInstallment.Status.OVERDUE)
                .map(RepaymentInstallment::remaining)
                .reduce(Money.ZERO, BigDecimal::add);
        LocalDate next = schedule.stream().filter(i -> i.remaining().signum() > 0)
                .map(RepaymentInstallment::getDueDate).findFirst().orElse(null);
        return new AgreementDetails(agreement, overdue, next);
    }

    @Transactional(readOnly = true)
    public List<StatusHistoryEntry> history(Actor actor, UUID id) {
        FinancingAgreement agreement = loadVisible(actor, id);
        return historyRepository.findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc(StatusHistoryEntry.AGREEMENT, agreement.getId());
    }

    @Transactional(readOnly = true)
    public Disbursement disbursement(Actor actor, UUID id) {
        FinancingAgreement agreement = loadVisible(actor, id);
        return disbursements.findByAgreementId(agreement.getId()).orElseThrow(() -> ResourceNotFoundException.disbursement(id));
    }

    /** Empty until the agreement is disbursed (the fee, and therefore the schedule, is fixed at disbursement). */
    @Transactional(readOnly = true)
    public List<RepaymentInstallment> schedule(Actor actor, UUID id) {
        FinancingAgreement agreement = loadVisible(actor, id);
        return installments.findByAgreementIdOrderBySequenceNoAsc(agreement.getId());
    }

    @Transactional(readOnly = true)
    public List<Repayment> repayments(Actor actor, UUID id) {
        FinancingAgreement agreement = loadVisible(actor, id);
        return repayments.findByAgreementIdOrderByReceivedAtAsc(agreement.getId());
    }

    @Transactional(readOnly = true)
    public List<RepaymentAllocation> allocations(UUID repaymentId) {
        return allocations.findByRepaymentId(repaymentId);
    }

    // ---- Lifecycle ----

    public FinancingAgreement approve(Actor actor, UUID id) {
        actor.require(Role.FINANCIER);
        FinancingAgreement agreement = loadForFinancier(actor, id);
        Instant now = clock.instant();
        AgreementStatus from = agreement.getStatus();
        agreement.approve(now);
        agreements.saveAndFlush(agreement);
        history.record(StatusHistoryEntry.AGREEMENT, id, from, agreement.getStatus(), actor, null, now);
        events.agreement(agreement, from, actor.userId());
        log.info("Agreement {} approved", id);
        return agreement;
    }

    public FinancingAgreement reject(Actor actor, UUID id, String reason) {
        actor.require(Role.FINANCIER);
        FinancingAgreement agreement = loadForFinancier(actor, id);
        Instant now = clock.instant();
        AgreementStatus from = agreement.getStatus();
        agreement.reject(reason, now);
        agreements.saveAndFlush(agreement);
        history.record(StatusHistoryEntry.AGREEMENT, id, from, agreement.getStatus(), actor, agreement.getStatusReason(), now);
        events.agreement(agreement, from, actor.userId());
        log.info("Agreement {} rejected", id);
        return agreement;
    }

    /** The seller may cancel while approval is pending; the financier may cancel an approved, undisbursed agreement. */
    public FinancingAgreement cancel(Actor actor, UUID id, String reason) {
        FinancingAgreement agreement = loadVisible(actor, id);
        boolean sellerMayCancel = actor.has(Role.SELLER) && agreement.getSellerId().equals(actor.userId())
                && agreement.getStatus() == AgreementStatus.PENDING_APPROVAL;
        boolean financierMayCancel = actor.has(Role.FINANCIER) && agreement.getFinancierId().equals(actor.userId())
                && agreement.getStatus() == AgreementStatus.APPROVED;
        if (!sellerMayCancel && !financierMayCancel) {
            if (agreement.getStatus().canTransitionTo(AgreementStatus.CANCELLED)) {
                throw new ConflictException("CANCELLATION_NOT_ALLOWED",
                        "The seller may cancel only while approval is pending; the financier only after approval");
            }
            agreement.ensureCanTransitionTo(AgreementStatus.CANCELLED);
        }
        Instant now = clock.instant();
        AgreementStatus from = agreement.getStatus();
        agreement.cancel(reason, now);
        agreements.saveAndFlush(agreement);
        history.record(StatusHistoryEntry.AGREEMENT, id, from, agreement.getStatus(), actor, agreement.getStatusReason(), now);
        events.agreement(agreement, from, actor.userId());
        log.info("Agreement {} cancelled", id);
        return agreement;
    }

    // ---- Money (simulated) ----

    /** Runs outside any caller transaction: the executor needs its own transactions to replay concurrent requests. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IdempotentExecutor.Result<Disbursement> disburse(Actor actor, UUID id, String idempotencyKey) {
        actor.require(Role.FINANCIER);
        return idempotent.run(OPERATION_DISBURSE, actor.userId(), idempotencyKey, IdempotentExecutor.hash(OPERATION_DISBURSE, id.toString()),
                () -> doDisburse(actor, id),
                Disbursement::getId,
                disbursementId -> disbursements.findById(disbursementId).orElseThrow());
    }

    private Disbursement doDisburse(Actor actor, UUID id) {
        FinancingAgreement agreement = agreements.findForUpdate(id)
                .filter(a -> a.getFinancierId().equals(actor.userId()))
                .orElseThrow(() -> ResourceNotFoundException.agreement(id));
        agreement.ensureCanTransitionTo(AgreementStatus.DISBURSED);
        Instant now = clock.instant();
        LocalDate today = today();
        long days = ChronoUnit.DAYS.between(today, agreement.getMaturityDate());
        if (days < agreement.getInstallmentCount()) {
            throw new BusinessRuleViolationException("MATURITY_TOO_SOON", "The invoice is too close to its due date to disburse");
        }
        BigDecimal fee = Money.financingFee(agreement.getAdvanceAmount(), agreement.getAnnualRate(), days, agreement.getProcessingFee());
        AgreementStatus from = agreement.getStatus();
        agreement.markDisbursed(fee, now);
        agreements.saveAndFlush(agreement);
        Disbursement disbursement = disbursements.saveAndFlush(Disbursement.simulated(UUID.randomUUID(), agreement, actor.userId(), now));
        installments.saveAll(RepaymentPlan.schedule(agreement.getId(), today, agreement.getMaturityDate(),
                agreement.getInstallmentCount(), agreement.getAdvanceAmount(), fee, UUID::randomUUID));
        history.record(StatusHistoryEntry.AGREEMENT, id, from, AgreementStatus.DISBURSED, actor, null, now);
        events.agreement(agreement, from, actor.userId());
        installments.flush();
        log.info("Agreement {} disbursed (simulated), disbursement {}", id, disbursement.getId());
        return disbursement;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IdempotentExecutor.Result<Repayment> recordRepayment(Actor actor, UUID id, BigDecimal amount, String idempotencyKey) {
        actor.require(Role.SELLER);
        BigDecimal exact = Money.exact(amount, "amount");
        return idempotent.run(OPERATION_REPAYMENT, actor.userId(), idempotencyKey,
                IdempotentExecutor.hash(OPERATION_REPAYMENT, id.toString(), exact.toPlainString()),
                () -> doRecordRepayment(actor, id, exact),
                Repayment::getId,
                repaymentId -> repayments.findById(repaymentId).orElseThrow());
    }

    private Repayment doRecordRepayment(Actor actor, UUID id, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new BusinessRuleViolationException("AMOUNT_NOT_POSITIVE", "amount must be positive");
        }
        FinancingAgreement agreement = agreements.findForUpdate(id)
                .filter(a -> a.getSellerId().equals(actor.userId()))
                .orElseThrow(() -> ResourceNotFoundException.agreement(id));
        if (agreement.getStatus() == AgreementStatus.SETTLED) {
            throw new ConflictException("AGREEMENT_SETTLED", "The agreement is already settled");
        }
        if (!agreement.getStatus().acceptsRepayments()) {
            throw new ConflictException("REPAYMENT_NOT_ALLOWED", "Repayments are not accepted while the agreement is " + agreement.getStatus());
        }
        Instant now = clock.instant();
        List<RepaymentInstallment> schedule = installments.findForUpdate(id);
        List<RepaymentPlan.Allocation> split = RepaymentPlan.allocate(schedule, amount);
        BigDecimal principal = split.stream().map(RepaymentPlan.Allocation::principalPart).reduce(Money.ZERO, BigDecimal::add);
        BigDecimal fee = split.stream().map(RepaymentPlan.Allocation::feePart).reduce(Money.ZERO, BigDecimal::add);
        AgreementStatus from = agreement.getStatus();
        agreement.applyRepayment(principal, fee, now);
        agreements.saveAndFlush(agreement);
        Repayment repayment = repayments.saveAndFlush(new Repayment(UUID.randomUUID(), id, principal, fee, actor.userId(), now));
        for (RepaymentPlan.Allocation allocation : split) {
            allocations.save(new RepaymentAllocation(repayment.getId(), allocation.installment().getId(),
                    allocation.principalPart(), allocation.feePart()));
        }
        events.repaymentRecorded(agreement, repayment, actor.userId());
        if (agreement.getStatus() != from) {
            history.record(StatusHistoryEntry.AGREEMENT, id, from, agreement.getStatus(), actor, null, now);
            events.agreement(agreement, from, actor.userId());
        }
        allocations.flush();
        log.info("Repayment {} recorded against agreement {}; status {}", repayment.getId(), id, agreement.getStatus());
        return repayment;
    }

    // ---- Loading with ownership ----

    private FinancingAgreement loadForFinancier(Actor actor, UUID id) {
        return agreements.findById(id).filter(a -> a.getFinancierId().equals(actor.userId()))
                .orElseThrow(() -> ResourceNotFoundException.agreement(id));
    }

    private FinancingAgreement loadVisible(Actor actor, UUID id) {
        FinancingAgreement agreement = agreements.findById(id).orElseThrow(() -> ResourceNotFoundException.agreement(id));
        if (actor.isAdmin()) {
            return agreement;
        }
        boolean seller = actor.has(Role.SELLER) && agreement.getSellerId().equals(actor.userId());
        boolean financier = actor.has(Role.FINANCIER) && agreement.getFinancierId().equals(actor.userId());
        if (!seller && !financier) {
            throw ResourceNotFoundException.agreement(id);
        }
        return agreement;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(properties.businessZone()));
    }
}
