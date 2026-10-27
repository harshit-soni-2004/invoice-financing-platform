package com.ifp.audit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ifp.audit.repository.AuditEventRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/audit-events")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    public record AuditEventResponse(long id, UUID eventId, String eventType, int schemaVersion, String producer,
                                     String aggregateType, String aggregateId, UUID actorId, String correlationId,
                                     Instant occurredAt, Instant recordedAt, JsonNode payload) {
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    private static final String NAME = "^[A-Za-z][A-Za-z0-9_.]{0,59}$";
    private static final String ID = "^[A-Za-z0-9-]{1,64}$";

    private final AuditEventRepository events;

    public AuditController(AuditEventRepository events) {
        this.events = events;
    }

    /** Newest first. {@code from} is inclusive and {@code to} exclusive, both ISO-8601 instants. */
    @GetMapping
    public PageResponse<AuditEventResponse> search(
            @RequestParam(required = false) @Pattern(regexp = NAME) String eventType,
            @RequestParam(required = false) @Pattern(regexp = NAME) String aggregateType,
            @RequestParam(required = false) @Pattern(regexp = ID) String aggregateId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        AuditEventRepository.Filter filter = new AuditEventRepository.Filter(eventType, aggregateType, aggregateId, actorId, from, to);
        long total = events.count(filter);
        List<AuditEventResponse> content = events.search(filter, page, size).stream()
                .map(e -> new AuditEventResponse(e.id(), e.eventId(), e.eventType(), e.schemaVersion(), e.producer(),
                        e.aggregateType(), e.aggregateId(), e.actorId(), e.correlationId(), e.occurredAt(), e.recordedAt(),
                        e.payload()))
                .toList();
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }
}
