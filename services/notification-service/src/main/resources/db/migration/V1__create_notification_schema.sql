-- Contact details learned from auth-service's UserRegistered events. The only place emails are stored outside auth.
CREATE TABLE recipients (
    user_id       BINARY(16)   NOT NULL,
    email         VARCHAR(254) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT pk_recipients PRIMARY KEY (user_id)
) ENGINE = InnoDB;

-- One row per (event, recipient): what was sent, or why it was skipped. Message bodies are not stored.
CREATE TABLE notifications (
    id            BINARY(16)   NOT NULL,
    event_id      BINARY(16)   NOT NULL,
    recipient_id  BINARY(16)   NOT NULL,
    event_type    VARCHAR(60)  NOT NULL,
    subject       VARCHAR(200) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    skip_reason   VARCHAR(40)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    sent_at       DATETIME(6)  NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT uk_notifications_event_recipient UNIQUE (event_id, recipient_id),
    CONSTRAINT ck_notifications_status CHECK (status IN ('SENT', 'SKIPPED')),
    CONSTRAINT ck_notifications_sent_at CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
) ENGINE = InnoDB;

CREATE INDEX ix_notifications_recipient_created ON notifications (recipient_id, created_at);

-- Every SMTP attempt, including failures that were retried or dead-lettered. Failed attempts are committed in their
-- own transaction, so they survive the rollback of the event they belong to. error holds an exception type only.
CREATE TABLE delivery_attempts (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    event_id      BINARY(16)   NOT NULL,
    recipient_id  BINARY(16)   NOT NULL,
    event_type    VARCHAR(60)  NOT NULL,
    outcome       VARCHAR(20)  NOT NULL,
    error         VARCHAR(100) NULL,
    attempted_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_delivery_attempts PRIMARY KEY (id),
    CONSTRAINT ck_delivery_attempts_outcome CHECK (outcome IN ('SENT', 'FAILED')),
    CONSTRAINT ck_delivery_attempts_error CHECK ((outcome = 'FAILED') = (error IS NOT NULL))
) ENGINE = InnoDB;

CREATE INDEX ix_delivery_attempts_event_recipient ON delivery_attempts (event_id, recipient_id);

-- Idempotent consumers: one row per (consumer, event), committed with the consumer's business effect.
CREATE TABLE processed_events (
    consumer      VARCHAR(60)  NOT NULL,
    event_id      BINARY(16)   NOT NULL,
    event_type    VARCHAR(60)  NOT NULL,
    processed_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (consumer, event_id)
) ENGINE = InnoDB;
