package com.ifp.financing.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Agreement lifecycle. "Overdue" is not a state: it is derived from installments past their due date with an unpaid
 * balance. Only APPROVED agreements can be disbursed, and money is only ever recorded, never moved.
 */
public enum AgreementStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    CANCELLED,
    DISBURSED,
    REPAYMENT_IN_PROGRESS,
    SETTLED;

    private static final Map<AgreementStatus, Set<AgreementStatus>> ALLOWED = new EnumMap<>(AgreementStatus.class);

    static {
        ALLOWED.put(PENDING_APPROVAL, EnumSet.of(APPROVED, REJECTED, CANCELLED));
        ALLOWED.put(APPROVED, EnumSet.of(DISBURSED, CANCELLED));
        ALLOWED.put(DISBURSED, EnumSet.of(REPAYMENT_IN_PROGRESS, SETTLED));
        ALLOWED.put(REPAYMENT_IN_PROGRESS, EnumSet.of(SETTLED));
        ALLOWED.put(REJECTED, EnumSet.noneOf(AgreementStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(AgreementStatus.class));
        ALLOWED.put(SETTLED, EnumSet.noneOf(AgreementStatus.class));
    }

    public boolean canTransitionTo(AgreementStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public Set<AgreementStatus> allowedTargets() {
        return Collections.unmodifiableSet(ALLOWED.get(this));
    }

    /** Statuses in which repayments are accepted. */
    public boolean acceptsRepayments() {
        return this == DISBURSED || this == REPAYMENT_IN_PROGRESS;
    }

    /** Statuses in which the invoice counts as financed or being financed (no other agreement may start). */
    public boolean isLive() {
        return this != REJECTED && this != CANCELLED;
    }
}
