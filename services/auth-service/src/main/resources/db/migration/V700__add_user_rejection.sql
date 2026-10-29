-- Administrators can reject a pending account. The status CHECK is widened (every existing value stays valid) and the
-- rejection details are nullable, so existing rows are unaffected.
ALTER TABLE users
    ADD COLUMN rejection_reason VARCHAR(500) NULL,
    ADD COLUMN rejected_at      DATETIME(6)  NULL;

ALTER TABLE users DROP CHECK ck_users_status;

ALTER TABLE users
    ADD CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'PENDING_APPROVAL', 'DISABLED', 'REJECTED'));
