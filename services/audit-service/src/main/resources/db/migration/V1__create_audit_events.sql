-- Append-only audit trail. Flyway runs as audit_migrator; the application user audit_svc has SELECT and INSERT only,
-- so recorded events cannot be changed or deleted through the service.
CREATE TABLE audit_events (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    event_id        BINARY(16)   NOT NULL,
    event_type      VARCHAR(60)  NOT NULL,
    schema_version  INT          NOT NULL,
    producer        VARCHAR(60)  NOT NULL,
    aggregate_type  VARCHAR(40)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    actor_id        BINARY(16)   NULL,
    correlation_id  VARCHAR(64)  NULL,
    occurred_at     DATETIME(6)  NOT NULL,
    recorded_at     DATETIME(6)  NOT NULL,
    -- Event payload with contact details (email, display name) removed.
    payload         JSON         NOT NULL,
    CONSTRAINT pk_audit_events PRIMARY KEY (id),
    CONSTRAINT uk_audit_events_event UNIQUE (event_id)
) ENGINE = InnoDB;

CREATE INDEX ix_audit_events_occurred ON audit_events (occurred_at, id);
CREATE INDEX ix_audit_events_aggregate ON audit_events (aggregate_type, aggregate_id, occurred_at);
CREATE INDEX ix_audit_events_actor ON audit_events (actor_id, occurred_at);
CREATE INDEX ix_audit_events_type ON audit_events (event_type, occurred_at);
