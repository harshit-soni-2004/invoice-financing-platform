-- Transactional outbox: rows are written in the business transaction and published by the outbox relay.
CREATE TABLE outbox_events (
    id               BINARY(16)   NOT NULL,
    event_type       VARCHAR(60)  NOT NULL,
    routing_key      VARCHAR(100) NOT NULL,
    aggregate_type   VARCHAR(40)  NOT NULL,
    aggregate_id     VARCHAR(64)  NOT NULL,
    envelope         JSON         NOT NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       VARCHAR(100) NULL,
    created_at       DATETIME(6)  NOT NULL,
    next_attempt_at  DATETIME(6)  NOT NULL,
    published_at     DATETIME(6)  NULL,
    -- Commit-independent write order; events of one transaction can share a timestamp.
    seq              BIGINT       NOT NULL AUTO_INCREMENT,
    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT uk_outbox_events_seq UNIQUE (seq)
) ENGINE = InnoDB;

CREATE INDEX ix_outbox_events_pending ON outbox_events (published_at, next_attempt_at, seq);
