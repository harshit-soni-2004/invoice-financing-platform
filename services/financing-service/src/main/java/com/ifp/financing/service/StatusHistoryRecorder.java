package com.ifp.financing.service;

import com.ifp.financing.domain.StatusHistoryEntry;
import com.ifp.financing.repository.StatusHistoryRepository;
import com.ifp.financing.security.Actor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
class StatusHistoryRecorder {

    private final StatusHistoryRepository history;

    StatusHistoryRecorder(StatusHistoryRepository history) {
        this.history = history;
    }

    void record(String entityType, UUID entityId, Enum<?> from, Enum<?> to, Actor actor, String reason, Instant now) {
        history.save(new StatusHistoryEntry(entityType, entityId, from, to, actor.userId(), actor.primaryRole(), reason, now));
    }

    void recordSystem(String entityType, UUID entityId, Enum<?> from, Enum<?> to, String reason, Instant now) {
        history.save(new StatusHistoryEntry(entityType, entityId, from, to, null, StatusHistoryEntry.SYSTEM_ROLE, reason, now));
    }
}
