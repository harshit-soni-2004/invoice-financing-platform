-- Platform users. Emails are stored lower-cased by the application; the database collation
-- (utf8mb4_0900_ai_ci) additionally makes the unique key case-insensitive.
CREATE TABLE users (
    id             BINARY(16)    NOT NULL,
    email          VARCHAR(254)  NOT NULL,
    password_hash  VARCHAR(100)  NOT NULL,
    display_name   VARCHAR(100)  NOT NULL,
    role           VARCHAR(20)   NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    version        BIGINT        NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('SELLER', 'FINANCIER', 'ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'PENDING_APPROVAL', 'DISABLED'))
);

CREATE INDEX ix_users_role_status_created ON users (role, status, created_at);
