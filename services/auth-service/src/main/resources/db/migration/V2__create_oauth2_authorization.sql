-- Spring Authorization Server's JDBC authorization store (schema shipped with SAS 1.5), adapted to the
-- platform conventions: DATETIME(6) holding UTC instead of TIMESTAMP. Token values are stored as issued;
-- access tokens are self-contained JWTs and the SPA client receives no refresh tokens.
CREATE TABLE oauth2_authorization (
    id                            VARCHAR(100)  NOT NULL,
    registered_client_id          VARCHAR(100)  NOT NULL,
    principal_name                VARCHAR(200)  NOT NULL,
    authorization_grant_type      VARCHAR(100)  NOT NULL,
    authorized_scopes             VARCHAR(1000) DEFAULT NULL,
    attributes                    BLOB          DEFAULT NULL,
    state                         VARCHAR(500)  DEFAULT NULL,
    authorization_code_value      BLOB          DEFAULT NULL,
    authorization_code_issued_at  DATETIME(6)   DEFAULT NULL,
    authorization_code_expires_at DATETIME(6)   DEFAULT NULL,
    authorization_code_metadata   BLOB          DEFAULT NULL,
    access_token_value            BLOB          DEFAULT NULL,
    access_token_issued_at        DATETIME(6)   DEFAULT NULL,
    access_token_expires_at       DATETIME(6)   DEFAULT NULL,
    access_token_metadata         BLOB          DEFAULT NULL,
    access_token_type             VARCHAR(100)  DEFAULT NULL,
    access_token_scopes           VARCHAR(1000) DEFAULT NULL,
    oidc_id_token_value           BLOB          DEFAULT NULL,
    oidc_id_token_issued_at       DATETIME(6)   DEFAULT NULL,
    oidc_id_token_expires_at      DATETIME(6)   DEFAULT NULL,
    oidc_id_token_metadata        BLOB          DEFAULT NULL,
    refresh_token_value           BLOB          DEFAULT NULL,
    refresh_token_issued_at       DATETIME(6)   DEFAULT NULL,
    refresh_token_expires_at      DATETIME(6)   DEFAULT NULL,
    refresh_token_metadata        BLOB          DEFAULT NULL,
    user_code_value               BLOB          DEFAULT NULL,
    user_code_issued_at           DATETIME(6)   DEFAULT NULL,
    user_code_expires_at          DATETIME(6)   DEFAULT NULL,
    user_code_metadata            BLOB          DEFAULT NULL,
    device_code_value             BLOB          DEFAULT NULL,
    device_code_issued_at         DATETIME(6)   DEFAULT NULL,
    device_code_expires_at        DATETIME(6)   DEFAULT NULL,
    device_code_metadata          BLOB          DEFAULT NULL,
    CONSTRAINT pk_oauth2_authorization PRIMARY KEY (id)
);

CREATE INDEX ix_oauth2_authorization_principal ON oauth2_authorization (principal_name);
CREATE INDEX ix_oauth2_authorization_access_expiry ON oauth2_authorization (access_token_expires_at);
