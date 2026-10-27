package com.ifp.financing.repository;

import com.ifp.financing.domain.StatusHistoryEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StatusHistoryRepository extends JpaRepository<StatusHistoryEntry, Long> {

    List<StatusHistoryEntry> findByEntityTypeAndEntityIdOrderByOccurredAtAscIdAsc(String entityType, UUID entityId);
}
