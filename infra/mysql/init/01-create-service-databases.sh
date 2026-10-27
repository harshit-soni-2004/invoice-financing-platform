#!/bin/bash
# Runs once, on first start of an empty MySQL data volume.
# Creates one database and one least-privilege user per service.
set -euo pipefail

require_safe() {
  local name="$1" value="${!1:-}"
  if [[ -z "$value" ]]; then
    echo "ERROR: $name is not set" >&2
    exit 1
  fi
  if [[ ! "$value" =~ ^[A-Za-z0-9_-]+$ ]]; then
    echo "ERROR: $name may only contain letters, digits, '-' and '_'" >&2
    exit 1
  fi
}

for var in AUTH_DB_PASSWORD INVOICE_DB_PASSWORD FINANCING_DB_PASSWORD \
           NOTIFICATION_DB_PASSWORD AUDIT_DB_MIGRATOR_PASSWORD AUDIT_DB_PASSWORD; do
  require_safe "$var"
done

MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS auth_db         CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS invoice_db      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS financing_db    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS notification_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS audit_db        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'auth_svc'@'%'         IDENTIFIED BY '${AUTH_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'invoice_svc'@'%'      IDENTIFIED BY '${INVOICE_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'financing_svc'@'%'    IDENTIFIED BY '${FINANCING_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'notification_svc'@'%' IDENTIFIED BY '${NOTIFICATION_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'audit_migrator'@'%'   IDENTIFIED BY '${AUDIT_DB_MIGRATOR_PASSWORD}';
CREATE USER IF NOT EXISTS 'audit_svc'@'%'        IDENTIFIED BY '${AUDIT_DB_PASSWORD}';

GRANT ALL PRIVILEGES ON auth_db.*         TO 'auth_svc'@'%';
GRANT ALL PRIVILEGES ON invoice_db.*      TO 'invoice_svc'@'%';
GRANT ALL PRIVILEGES ON financing_db.*    TO 'financing_svc'@'%';
GRANT ALL PRIVILEGES ON notification_db.* TO 'notification_svc'@'%';

-- Audit trail is append-only: Flyway runs as audit_migrator, the application as audit_svc.
GRANT ALL PRIVILEGES ON audit_db.*        TO 'audit_migrator'@'%';
GRANT SELECT, INSERT ON audit_db.*        TO 'audit_svc'@'%';

FLUSH PRIVILEGES;
SQL

echo "Service databases and users created."
