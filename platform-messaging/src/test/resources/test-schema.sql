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
    seq              BIGINT       NOT NULL AUTO_INCREMENT,
    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT uk_outbox_events_seq UNIQUE (seq)
) ENGINE = InnoDB;

CREATE TABLE processed_events (
    consumer      VARCHAR(60)  NOT NULL,
    event_id      BINARY(16)   NOT NULL,
    event_type    VARCHAR(60)  NOT NULL,
    processed_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (consumer, event_id)
) ENGINE = InnoDB;

CREATE TABLE effects (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    event_id  VARCHAR(36) NOT NULL,
    CONSTRAINT pk_effects PRIMARY KEY (id)
) ENGINE = InnoDB;
