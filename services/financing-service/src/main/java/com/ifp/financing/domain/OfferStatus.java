package com.ifp.financing.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * OFFERED is the only open state. ACCEPTED (seller), REJECTED (seller), WITHDRAWN (financier), EXPIRED (validity passed)
 * and CLOSED (another offer on the same invoice was accepted, or the invoice stopped being available) are final.
 */
public enum OfferStatus {
    OFFERED,
    ACCEPTED,
    REJECTED,
    WITHDRAWN,
    EXPIRED,
    CLOSED;

    private static final Set<OfferStatus> FROM_OFFERED = EnumSet.of(ACCEPTED, REJECTED, WITHDRAWN, EXPIRED, CLOSED);

    public boolean canTransitionTo(OfferStatus target) {
        return this == OFFERED && FROM_OFFERED.contains(target);
    }

    public boolean isTerminal() {
        return this != OFFERED;
    }
}
