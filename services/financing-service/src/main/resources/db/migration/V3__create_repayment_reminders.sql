-- One row per reminder published for an installment, so each reminder kind goes out at most once.
CREATE TABLE repayment_reminders (
    installment_id  BINARY(16)   NOT NULL,
    kind            VARCHAR(20)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    CONSTRAINT pk_repayment_reminders PRIMARY KEY (installment_id, kind),
    CONSTRAINT fk_repayment_reminders_installment FOREIGN KEY (installment_id) REFERENCES repayment_installments (id),
    CONSTRAINT ck_repayment_reminders_kind CHECK (kind IN ('DUE_SOON', 'OVERDUE'))
) ENGINE = InnoDB;
