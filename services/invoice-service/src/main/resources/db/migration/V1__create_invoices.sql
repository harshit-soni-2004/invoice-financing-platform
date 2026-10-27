-- Invoice aggregate and its lifecycle history.
-- Timestamps are DATETIME(6) holding UTC values (no session time-zone conversion, no 2038 limit).
-- Money is DECIMAL(19,2); UUIDs are BINARY(16).

CREATE TABLE invoices (
    id                  BINARY(16)     NOT NULL,
    seller_id           BINARY(16)     NOT NULL,
    invoice_number      VARCHAR(50)    NOT NULL,
    buyer_name          VARCHAR(200)   NOT NULL,
    buyer_gstin         VARCHAR(15)    NULL,
    description         VARCHAR(500)   NULL,
    amount              DECIMAL(19, 2) NOT NULL,
    currency            VARCHAR(3)     NOT NULL DEFAULT 'INR',
    issue_date          DATE           NOT NULL,
    due_date            DATE           NOT NULL,
    status              VARCHAR(30)    NOT NULL,
    status_reason       VARCHAR(500)   NULL,
    submitted_at        DATETIME(6)    NULL,
    buyer_confirmed_at  DATETIME(6)    NULL,
    buyer_confirmed_by  BINARY(16)     NULL,
    verified_at         DATETIME(6)    NULL,
    verified_by         BINARY(16)     NULL,
    financed_at         DATETIME(6)    NULL,
    settled_at          DATETIME(6)    NULL,
    version             BIGINT         NOT NULL DEFAULT 0,
    created_at          DATETIME(6)    NOT NULL,
    updated_at          DATETIME(6)    NOT NULL,

    CONSTRAINT pk_invoices PRIMARY KEY (id),
    -- The default collation is case-insensitive, so "inv-1" and "INV-1" count as the same number.
    CONSTRAINT uk_invoices_seller_number UNIQUE (seller_id, invoice_number),
    CONSTRAINT ck_invoices_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_invoices_currency_inr CHECK (currency = 'INR'),
    CONSTRAINT ck_invoices_due_after_issue CHECK (due_date > issue_date),
    CONSTRAINT ck_invoices_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'BUYER_CONFIRMED', 'VERIFIED',
        'FINANCING_AVAILABLE', 'FINANCED', 'SETTLED', 'REJECTED', 'CANCELLED'))
) ENGINE = InnoDB;

CREATE INDEX ix_invoices_seller_status_created ON invoices (seller_id, status, created_at);
CREATE INDEX ix_invoices_status_created ON invoices (status, created_at);

CREATE TABLE invoice_status_history (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    invoice_id   BINARY(16)   NOT NULL,
    from_status  VARCHAR(30)  NULL,
    to_status    VARCHAR(30)  NOT NULL,
    actor_id     BINARY(16)   NOT NULL,
    actor_role   VARCHAR(20)  NOT NULL,
    reason       VARCHAR(500) NULL,
    occurred_at  DATETIME(6)  NOT NULL,

    CONSTRAINT pk_invoice_status_history PRIMARY KEY (id),
    CONSTRAINT fk_invoice_status_history_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id),
    CONSTRAINT ck_invoice_status_history_changes CHECK (from_status IS NULL OR from_status <> to_status)
) ENGINE = InnoDB;

CREATE INDEX ix_invoice_status_history_invoice ON invoice_status_history (invoice_id, occurred_at, id);
