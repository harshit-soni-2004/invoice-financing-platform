-- Penalty interest on overdue installments. Accrued once per installment per business day by the overdue job, capped
-- per installment, and repaid before fee and principal within each installment (oldest installment first).
-- Existing rows start with zero penalty, so balances and settlement are unchanged for them.

ALTER TABLE repayment_installments
    ADD COLUMN penalty_accrued         DECIMAL(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN penalty_paid            DECIMAL(19, 2) NOT NULL DEFAULT 0,
    -- Last business day already accrued (or examined); NULL until the installment is first overdue.
    ADD COLUMN penalty_accrued_through DATE           NULL,
    ADD CONSTRAINT ck_repayment_installments_penalty CHECK (penalty_accrued >= 0 AND penalty_paid >= 0
        AND penalty_paid <= penalty_accrued);

ALTER TABLE financing_agreements
    ADD COLUMN penalty_accrued DECIMAL(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN penalty_repaid  DECIMAL(19, 2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_financing_agreements_penalty CHECK (penalty_accrued >= 0 AND penalty_repaid >= 0
        AND penalty_repaid <= penalty_accrued);

ALTER TABLE repayments
    ADD COLUMN penalty_component DECIMAL(19, 2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_repayments_penalty CHECK (penalty_component >= 0);
ALTER TABLE repayments DROP CHECK ck_repayments_amount;
ALTER TABLE repayments
    ADD CONSTRAINT ck_repayments_amount CHECK (amount > 0 AND amount = principal_component + fee_component + penalty_component);

ALTER TABLE repayment_allocations
    ADD COLUMN penalty_part DECIMAL(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE repayment_allocations DROP CHECK ck_repayment_allocations_parts;
ALTER TABLE repayment_allocations
    ADD CONSTRAINT ck_repayment_allocations_parts CHECK (principal_part >= 0 AND fee_part >= 0 AND penalty_part >= 0
        AND principal_part + fee_part + penalty_part > 0);

-- One row per installment per accrued day: the primary key makes accrual idempotent even if two sweeps overlap.
CREATE TABLE penalty_accruals (
    installment_id  BINARY(16)     NOT NULL,
    accrual_date    DATE           NOT NULL,
    agreement_id    BINARY(16)     NOT NULL,
    -- Unpaid principal + fee the penalty was charged on, and the daily rate in force (fraction, 0.000500 = 0.05%).
    base_amount     DECIMAL(19, 2) NOT NULL,
    daily_rate      DECIMAL(9, 6)  NOT NULL,
    amount          DECIMAL(19, 2) NOT NULL,
    created_at      DATETIME(6)    NOT NULL,

    CONSTRAINT pk_penalty_accruals PRIMARY KEY (installment_id, accrual_date),
    CONSTRAINT fk_penalty_accruals_installment FOREIGN KEY (installment_id) REFERENCES repayment_installments (id),
    CONSTRAINT fk_penalty_accruals_agreement FOREIGN KEY (agreement_id) REFERENCES financing_agreements (id),
    CONSTRAINT ck_penalty_accruals_amounts CHECK (amount > 0 AND base_amount > 0 AND daily_rate > 0)
) ENGINE = InnoDB;

CREATE INDEX ix_penalty_accruals_agreement ON penalty_accruals (agreement_id, accrual_date);
