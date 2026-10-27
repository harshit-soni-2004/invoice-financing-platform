#!/bin/bash
# Verifies per-service database isolation. Run inside the MySQL container:
#   docker exec -i ifp-mysql bash < scripts/verify-mysql-privileges.sh
set -uo pipefail

failures=0

run() {
  MYSQL_PWD="$2" mysql -h127.0.0.1 -u"$1" -N -e "$3" >/dev/null 2>&1
}

expect() {
  local label="$1" expected="$2"; shift 2
  if run "$@"; then actual=allowed; else actual=denied; fi
  if [[ "$actual" == "$expected" ]]; then
    printf 'PASS  %-45s %s\n' "$label" "$actual"
  else
    printf 'FAIL  %-45s expected %s, got %s\n' "$label" "$expected" "$actual"
    failures=$((failures + 1))
  fi
}

expect "auth_svc uses auth_db"               allowed auth_svc         "$AUTH_DB_PASSWORD"         "USE auth_db"
expect "invoice_svc uses invoice_db"         allowed invoice_svc      "$INVOICE_DB_PASSWORD"      "USE invoice_db"
expect "financing_svc uses financing_db"     allowed financing_svc    "$FINANCING_DB_PASSWORD"    "USE financing_db"
expect "notification_svc uses notification_db" allowed notification_svc "$NOTIFICATION_DB_PASSWORD" "USE notification_db"
expect "invoice_svc cannot use financing_db" denied  invoice_svc      "$INVOICE_DB_PASSWORD"      "USE financing_db"
expect "financing_svc cannot use invoice_db" denied  financing_svc    "$FINANCING_DB_PASSWORD"    "USE invoice_db"

expect "audit_migrator can create table"     allowed audit_migrator   "$AUDIT_DB_MIGRATOR_PASSWORD" "CREATE TABLE audit_db.privilege_probe (id INT PRIMARY KEY)"
expect "audit_svc can insert"                allowed audit_svc        "$AUDIT_DB_PASSWORD"        "INSERT INTO audit_db.privilege_probe VALUES (1)"
expect "audit_svc can select"                allowed audit_svc        "$AUDIT_DB_PASSWORD"        "SELECT * FROM audit_db.privilege_probe"
expect "audit_svc cannot update"             denied  audit_svc        "$AUDIT_DB_PASSWORD"        "UPDATE audit_db.privilege_probe SET id = 2"
expect "audit_svc cannot delete"             denied  audit_svc        "$AUDIT_DB_PASSWORD"        "DELETE FROM audit_db.privilege_probe"
expect "audit_svc cannot drop"               denied  audit_svc        "$AUDIT_DB_PASSWORD"        "DROP TABLE audit_db.privilege_probe"
run audit_migrator "$AUDIT_DB_MIGRATOR_PASSWORD" "DROP TABLE IF EXISTS audit_db.privilege_probe"

tz=$(MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot -N -e "SELECT @@global.time_zone" 2>/dev/null)
if [[ "$tz" == "+00:00" ]]; then echo "PASS  server time zone is UTC"; else echo "FAIL  server time zone is $tz"; failures=$((failures + 1)); fi

echo "failures=$failures"
exit "$failures"
