package com.ifp.financing.repository;

import com.ifp.financing.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByOperationAndActorIdAndIdempotencyKey(String operation, UUID actorId, String idempotencyKey);
}
