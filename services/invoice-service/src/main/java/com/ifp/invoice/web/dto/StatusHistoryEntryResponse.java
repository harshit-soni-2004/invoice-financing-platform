package com.ifp.invoice.web.dto;

import com.ifp.invoice.domain.InvoiceStatus;
import com.ifp.invoice.domain.InvoiceStatusHistory;

import java.time.Instant;
import java.util.UUID;

/** {@code actorId} is only disclosed to administrators; sellers see the acting role. */
public record StatusHistoryEntryResponse(
        InvoiceStatus fromStatus,
        InvoiceStatus toStatus,
        String actorRole,
        UUID actorId,
        String reason,
        Instant occurredAt) {

    public static StatusHistoryEntryResponse from(InvoiceStatusHistory entry, boolean includeActorId) {
        return new StatusHistoryEntryResponse(entry.getFromStatus(), entry.getToStatus(), entry.getActorRole(),
                includeActorId ? entry.getActorId() : null, entry.getReason(), entry.getOccurredAt());
    }
}
