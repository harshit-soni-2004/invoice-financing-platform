# Invoice Financing Platform

A portfolio-quality **simulation** of invoice financing: sellers submit invoices, financiers make offers, accepted offers are disbursed (simulated), and repayments are recorded until settlement.

> No real banking, payment gateway or money movement is involved. All amounts are in INR and handled as `BigDecimal` / `DECIMAL(19,2)`.

**Status:** Phase 2 is complete. invoice-service has its invoice lifecycle, Flyway schema, REST API and JWT validation. auth-service and the gateway are Phase 3. See [docs/architecture.md](docs/architecture.md) for the design and roadmap.

## Stack

Java 21 · Spring Boot 3.5 · Spring Cloud Gateway 2025.0 · Spring Authorization Server · MySQL 8.4 · Flyway · RabbitMQ 4.1 · Redis 7.4 · MailHog · Angular 19 · Docker Compose · Testcontainers · GitHub Actions

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 21 |
| Docker Desktop | Engine 29+ with Compose v2 |
| Node.js (frontend, Phase 6) | 22 LTS (Angular 19 does not support Node 24) |

Maven does not need to be installed. Use the bundled wrapper (`mvnw` / `mvnw.cmd`).

## Local infrastructure

```bash
cp .env.example .env          # then replace every replace-me-* value
docker compose up -d --wait   # MySQL, RabbitMQ, Redis, MailHog
docker compose ps             # all four should be "healthy"
```

| Component | Host address |
|---|---|
| MySQL | `127.0.0.1:3307` (3307 avoids clashing with a local MySQL install) |
| RabbitMQ AMQP / UI | `127.0.0.1:5672` / http://localhost:15672 |
| Redis | `127.0.0.1:6379` |
| MailHog SMTP / UI | `127.0.0.1:1025` / http://localhost:8025 |

All ports bind to `127.0.0.1` only. On first start, MySQL creates one database and one least-privilege user per service. To verify isolation:

```bash
docker exec -i ifp-mysql bash < scripts/verify-mysql-privileges.sh
```

Stop with `docker compose down`. Data survives in named volumes. `docker compose down -v` **deletes all local data**.

## Build and test

```bash
./mvnw verify        # Windows: .\mvnw.cmd verify
```

Unit tests (`*Test`, `*Tests`) run in the `test` phase, and integration tests (`*IT`, Testcontainers) run in `verify`. Integration tests need Docker running. They start their own disposable MySQL 8.4 container and never touch the Compose database.

## Running invoice-service locally

With the Compose infrastructure up:

```powershell
# PowerShell; uses the invoice_svc password from your .env without printing it
$env:DB_PASSWORD = (Get-Content .env | Select-String '^INVOICE_DB_PASSWORD=').Line.Split('=', 2)[1]
$env:SPRING_PROFILES_ACTIVE = 'dev'   # optional: loads DEMO- seed invoices (development only)
.\mvnw.cmd -pl services/invoice-service -am package -DskipTests
java -jar services/invoice-service/target/invoice-service-0.1.0-SNAPSHOT.jar
```

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `3307` / `invoice_db` | MySQL location |
| `DB_USERNAME` / `DB_PASSWORD` | `invoice_svc` / (none) | Service database user |
| `AUTH_ISSUER_URI` | `http://localhost:9000` | Expected `iss` claim |
| `AUTH_JWK_SET_URI` | `http://localhost:9000/oauth2/jwks` | Where signing keys are fetched |
| `AUTH_AUDIENCE` | `ifp-api` | Required `aud` value |

`GET http://localhost:8081/actuator/health` is public. Every `/api/invoices` endpoint needs a bearer token whose `sub` is a user UUID and whose `roles` claim includes `SELLER` or `ADMIN`. Real tokens come from auth-service in Phase 3. Until then the API is exercised through the integration tests.

The `dev` profile seed is demo data only: fictional sellers and buyers, with invoice numbers prefixed `DEMO-`. Production migrations live in `db/migration`, and the seed lives in `db/seed-dev`.

## Logging

Logs go to stdout. Set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` to emit structured JSON (used in containers). Every request carries an `X-Correlation-Id`, which appears in logs and response headers.

## Troubleshooting

- **`ports are not available ... 3306`**: another MySQL is running. Change `MYSQL_PORT` in `.env`.
- **MySQL init script fails with `/bin/bash^M`**: the script has Windows line endings. `.gitattributes` forces LF. If it happened anyway, convert the file, then `docker compose down`, `docker volume rm invoice-financing_mysql-data` (deletes local MySQL data) and start again. Init scripts only run on an empty volume.
