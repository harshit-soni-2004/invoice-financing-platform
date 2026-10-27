package com.ifp.invoice.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Invoice lifecycle. Financing progress (offers, agreements, repayments) is tracked separately by
 * financing-service; this service only reaches FINANCED / SETTLED when told so by financing events.
 */
public enum InvoiceStatus {
    DRAFT,
    SUBMITTED,
    BUYER_CONFIRMED,
    VERIFIED,
    FINANCING_AVAILABLE,
    FINANCED,
    SETTLED,
    REJECTED,
    CANCELLED;

    private static final Map<InvoiceStatus, Set<InvoiceStatus>> ALLOWED = new EnumMap<>(InvoiceStatus.class);

    static {
        ALLOWED.put(DRAFT, EnumSet.of(SUBMITTED, CANCELLED));
        ALLOWED.put(SUBMITTED, EnumSet.of(BUYER_CONFIRMED, REJECTED, CANCELLED));
        ALLOWED.put(BUYER_CONFIRMED, EnumSet.of(VERIFIED, REJECTED));
        ALLOWED.put(VERIFIED, EnumSet.of(FINANCING_AVAILABLE, CANCELLED));
        ALLOWED.put(FINANCING_AVAILABLE, EnumSet.of(FINANCED));
        ALLOWED.put(FINANCED, EnumSet.of(SETTLED));
        ALLOWED.put(SETTLED, EnumSet.noneOf(InvoiceStatus.class));
        ALLOWED.put(REJECTED, EnumSet.noneOf(InvoiceStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(InvoiceStatus.class));
    }

    public boolean canTransitionTo(InvoiceStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public Set<InvoiceStatus> allowedTargets() {
        return Collections.unmodifiableSet(ALLOWED.get(this));
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
