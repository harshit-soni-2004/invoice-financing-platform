-- financing-service schema. Same conventions as invoice-service:
-- UUIDs are BINARY(16), money is DECIMAL(19,2), rates are DECIMAL(7,4) fractions (0.1200 = 12% p.a.),
-- timestamps are DATETIME(6) holding UTC values.

-- Local read model of invoices fed by invoice-service events (Phase 5). Never written by users.
CREATE TABLE financeable_invoices (
    invoice_id         BINARY(16)     NOT NULL,
    seller_id          BINARY(16)     NOT NULL,
    invoice_number     VARCHAR(50)    NOT NULL,
    buyer_name         VARCHAR(200)   NOT NULL,
    amount             DECIMAL(19, 2) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    issue_date         DATE           NOT NULL,
    due_date           DATE           NOT NULL,
    invoice_status     VARCHAR(30)    NOT NULL,
    -- invoice-service row version of the state captured here; older or equal versions are ignored.
    source_version     BIGINT         NOT NULL,
    source_updated_at  DATETIME(6)    NOT NULL,
    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         DATETIME(6)    NOT NULL,
    updated_at         DATETIME(6)    NOT NULL,

    CONSTRAINT pk_financeable_invoices PRIMARY KEY (invoice_id),
    CONSTRAINT ck_financeable_invoices_amount CHECK (amount > 0),
    CONSTRAINT ck_financeable_invoices_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_financeable_invoices_dates CHECK (due_date > issue_date),
    CONSTRAINT ck_financeable_invoices_source_version CHECK (source_version >= 0)
) ENGINE = InnoDB;

CREATE INDEX ix_financeable_invoices_status_due ON financeable_invoices (invoice_status, due_date);
CREATE INDEX ix_financeable_invoices_seller ON financeable_invoices (seller_id);

CREATE TABLE financing_offers (
    id                 BINARY(16)     NOT NULL,
    invoice_id         BINARY(16)     NOT NULL,
    seller_id          BINARY(16)     NOT NULL,
    financier_id       BINARY(16)     NOT NULL,
    advance_amount     DECIMAL(19, 2) NOT NULL,
    annual_rate        DECIMAL(7, 4)  NOT NULL,
    processing_fee     DECIMAL(19, 2) NOT NULL,
    installment_count  INT            NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    expires_at         DATETIME(6)    NOT NULL,
    status             VARCHAR(20)    NOT NULL,
    status_reason      VARCHAR(500)   NULL,
    decided_at         DATETIME(6)    NULL,
    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         DATETIME(6)    NOT NULL,
    updated_at         DATETIME(6)    NOT NULL,
    -- Non-NULL only while the offer is open, so each financier has at most one open offer per invoice.
    open_financier_id  BINARY(16) AS (CASE WHEN status = 'OFFERED' THEN financier_id END) STORED,

    CONSTRAINT pk_financing_offers PRIMARY KEY (id),
    CONSTRAINT fk_financing_offers_invoice FOREIGN KEY (invoice_id) REFERENCES financeable_invoices (invoice_id),
    CONSTRAINT uk_financing_offers_open_per_financier UNIQUE (invoice_id, open_financier_id),
    CONSTRAINT ck_financing_offers_advance CHECK (advance_amount > 0),
    CONSTRAINT ck_financing_offers_rate CHECK (annual_rate > 0 AND annual_rate < 1),
    CONSTRAINT ck_financing_offers_fee CHECK (processing_fee >= 0),
    CONSTRAINT ck_financing_offers_installments CHECK (installment_count BETWEEN 1 AND 60),
    CONSTRAINT ck_financing_offers_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_financing_offers_status CHECK (status IN ('OFFERED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED', 'CLOSED'))
) ENGINE = InnoDB;

CREATE INDEX ix_financing_offers_financier ON financing_offers (financier_id, status, created_at);
CREATE INDEX ix_financing_offers_seller ON financing_offers (seller_id, status, created_at);
CREATE INDEX ix_financing_offers_status_expiry ON financing_offers (status, expires_at);

CREATE TABLE financing_agreements (
    id                    BINARY(16)     NOT NULL,
    offer_id              BINARY(16)     NOT NULL,
    invoice_id            BINARY(16)     NOT NULL,
    seller_id             BINARY(16)     NOT NULL,
    financier_id          BINARY(16)     NOT NULL,
    invoice_amount        DECIMAL(19, 2) NOT NULL,
    advance_amount        DECIMAL(19, 2) NOT NULL,
    annual_rate           DECIMAL(7, 4)  NOT NULL,
    processing_fee        DECIMAL(19, 2) NOT NULL,
    installment_count     INT            NOT NULL,
    currency              VARCHAR(3)     NOT NULL,
    maturity_date         DATE           NOT NULL,
    status                VARCHAR(30)    NOT NULL,
    status_reason         VARCHAR(500)   NULL,
    -- Fixed at disbursement (the fee depends on the number of days to maturity).
    fee_amount            DECIMAL(19, 2) NULL,
    total_repayable       DECIMAL(19, 2) NULL,
    principal_repaid      DECIMAL(19, 2) NOT NULL DEFAULT 0,
    fee_repaid            DECIMAL(19, 2) NOT NULL DEFAULT 0,
    approved_at           DATETIME(6)    NULL,
    disbursed_at          DATETIME(6)    NULL,
    settled_at            DATETIME(6)    NULL,
    version               BIGINT         NOT NULL DEFAULT 0,
    created_at            DATETIME(6)    NOT NULL,
    updated_at            DATETIME(6)    NOT NULL,
    -- Non-NULL while the agreement is live, so an invoice can never be financed twice.
    live_invoice_id       BINARY(16) AS (CASE WHEN status IN ('PENDING_APPROVAL', 'APPROVED', 'DISBURSED',
        'REPAYMENT_IN_PROGRESS', 'SETTLED') THEN invoice_id END) STORED,

    CONSTRAINT pk_financing_agreements PRIMARY KEY (id),
    CONSTRAINT uk_financing_agreements_offer UNIQUE (offer_id),
    CONSTRAINT uk_financing_agreements_live_invoice UNIQUE (live_invoice_id),
    CONSTRAINT fk_financing_agreements_offer FOREIGN KEY (offer_id) REFERENCES financing_offers (id),
    CONSTRAINT fk_financing_agreements_invoice FOREIGN KEY (invoice_id) REFERENCES financeable_invoices (invoice_id),
    CONSTRAINT ck_financing_agreements_advance CHECK (advance_amount > 0 AND advance_amount <= invoice_amount),
    CONSTRAINT ck_financing_agreements_repaid CHECK (principal_repaid >= 0 AND fee_repaid >= 0
        AND principal_repaid <= advance_amount AND (fee_amount IS NULL OR fee_repaid <= fee_amount)),
    CONSTRAINT ck_financing_agreements_total CHECK (total_repayable IS NULL OR total_repayable = advance_amount + fee_amount),
    CONSTRAINT ck_financing_agreements_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_financing_agreements_status CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED',
        'DISBURSED', 'REPAYMENT_IN_PROGRESS', 'SETTLED'))
) ENGINE = InnoDB;

CREATE INDEX ix_financing_agreements_seller ON financing_agreements (seller_id, status, created_at);
CREATE INDEX ix_financing_agreements_financier ON financing_agreements (financier_id, status, created_at);

-- Simulated disbursement: a record only, no money moves. At most one per agreement.
CREATE TABLE disbursements (
    id             BINARY(16)     NOT NULL,
    agreement_id   BINARY(16)     NOT NULL,
    amount         DECIMAL(19, 2) NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    reference      VARCHAR(40)    NOT NULL,
    initiated_by   BINARY(16)     NOT NULL,
    disbursed_at   DATETIME(6)    NOT NULL,

    CONSTRAINT pk_disbursements PRIMARY KEY (id),
    CONSTRAINT uk_disbursements_agreement UNIQUE (agreement_id),
    CONSTRAINT uk_disbursements_reference UNIQUE (reference),
    CONSTRAINT fk_disbursements_agreement FOREIGN KEY (agreement_id) REFERENCES financing_agreements (id),
    CONSTRAINT ck_disbursements_amount CHECK (amount > 0),
    CONSTRAINT ck_disbursements_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_disbursements_status CHECK (status IN ('SIMULATED_COMPLETED'))
) ENGINE = InnoDB;

CREATE TABLE repayment_installments (
    id              BINARY(16)     NOT NULL,
    agreement_id    BINARY(16)     NOT NULL,
    sequence_no     INT            NOT NULL,
    due_date        DATE           NOT NULL,
    principal_due   DECIMAL(19, 2) NOT NULL,
    fee_due         DECIMAL(19, 2) NOT NULL,
    principal_paid  DECIMAL(19, 2) NOT NULL DEFAULT 0,
    fee_paid        DECIMAL(19, 2) NOT NULL DEFAULT 0,
    version         BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT pk_repayment_installments PRIMARY KEY (id),
    CONSTRAINT uk_repayment_installments_sequence UNIQUE (agreement_id, sequence_no),
    CONSTRAINT fk_repayment_installments_agreement FOREIGN KEY (agreement_id) REFERENCES financing_agreements (id),
    CONSTRAINT ck_repayment_installments_amounts CHECK (principal_due >= 0 AND fee_due >= 0
        AND principal_paid >= 0 AND fee_paid >= 0 AND principal_paid <= principal_due AND fee_paid <= fee_due),
    CONSTRAINT ck_repayment_installments_sequence CHECK (sequence_no >= 1)
) ENGINE = InnoDB;

CREATE TABLE repayments (
    id                   BINARY(16)     NOT NULL,
    agreement_id         BINARY(16)     NOT NULL,
    amount               DECIMAL(19, 2) NOT NULL,
    principal_component  DECIMAL(19, 2) NOT NULL,
    fee_component        DECIMAL(19, 2) NOT NULL,
    currency             VARCHAR(3)     NOT NULL,
    recorded_by          BINARY(16)     NOT NULL,
    received_at          DATETIME(6)    NOT NULL,

    CONSTRAINT pk_repayments PRIMARY KEY (id),
    CONSTRAINT fk_repayments_agreement FOREIGN KEY (agreement_id) REFERENCES financing_agreements (id),
    CONSTRAINT ck_repayments_amount CHECK (amount > 0 AND amount = principal_component + fee_component),
    CONSTRAINT ck_repayments_components CHECK (principal_component >= 0 AND fee_component >= 0),
    CONSTRAINT ck_repayments_currency CHECK (currency = 'INR')
) ENGINE = InnoDB;

CREATE INDEX ix_repayments_agreement ON repayments (agreement_id, received_at);

-- How each repayment was split across installments (fee first, oldest installment first).
CREATE TABLE repayment_allocations (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    repayment_id    BINARY(16)     NOT NULL,
    installment_id  BINARY(16)     NOT NULL,
    principal_part  DECIMAL(19, 2) NOT NULL,
    fee_part        DECIMAL(19, 2) NOT NULL,

    CONSTRAINT pk_repayment_allocations PRIMARY KEY (id),
    CONSTRAINT fk_repayment_allocations_repayment FOREIGN KEY (repayment_id) REFERENCES repayments (id),
    CONSTRAINT fk_repayment_allocations_installment FOREIGN KEY (installment_id) REFERENCES repayment_installments (id),
    CONSTRAINT ck_repayment_allocations_parts CHECK (principal_part >= 0 AND fee_part >= 0 AND principal_part + fee_part > 0)
) ENGINE = InnoDB;

-- Replay protection for non-idempotent POSTs (disbursement, repayment). Scoped per caller and operation.
CREATE TABLE idempotency_records (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    operation        VARCHAR(40)  NOT NULL,
    actor_id         BINARY(16)   NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    resource_id      BINARY(16)   NOT NULL,
    created_at       DATETIME(6)  NOT NULL,

    CONSTRAINT pk_idempotency_records PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_records_key UNIQUE (operation, actor_id, idempotency_key)
) ENGINE = InnoDB;

CREATE TABLE financing_status_history (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    entity_type  VARCHAR(20)  NOT NULL,
    entity_id    BINARY(16)   NOT NULL,
    from_status  VARCHAR(30)  NULL,
    to_status    VARCHAR(30)  NOT NULL,
    actor_id     BINARY(16)   NULL,
    actor_role   VARCHAR(20)  NOT NULL,
    reason       VARCHAR(500) NULL,
    occurred_at  DATETIME(6)  NOT NULL,

    CONSTRAINT pk_financing_status_history PRIMARY KEY (id),
    CONSTRAINT ck_financing_status_history_type CHECK (entity_type IN ('OFFER', 'AGREEMENT')),
    CONSTRAINT ck_financing_status_history_changes CHECK (from_status IS NULL OR from_status <> to_status)
) ENGINE = InnoDB;

CREATE INDEX ix_financing_status_history_entity ON financing_status_history (entity_type, entity_id, occurred_at, id);
