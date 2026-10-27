# Invoice Financing Platform

A portfolio-quality **simulation** of invoice financing: sellers submit invoices, financiers make offers, accepted offers are disbursed (simulated), and repayments are recorded until settlement.

> No real banking, payment gateway or money movement is involved. All amounts are in INR and handled as `BigDecimal` / `DECIMAL(19,2)`.

**Status:** Phase 5 is complete. auth-service issues RS256 tokens through Authorization Code + PKCE, the gateway validates them, routes `/api/**`, applies CORS and Redis rate limits, and each service validates every token again itself. financing-service implements offers from competing financiers, agreements with an explicit state machine, idempotent simulated disbursement, repayment schedules, partial and full repayments, overdue tracking and settlement. Services exchange events over RabbitMQ through a transactional outbox, with idempotent consumers, bounded retries and dead-letter queues. Listed invoices reach financing-service's read model, disbursement and settlement update the invoice, notification-service emails the parties through MailHog, and audit-service keeps an append-only trail of every event. See [docs/architecture.md](docs/architecture.md) for the design, the financial rules and the roadmap.

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

Unit tests (`*Test`, `*Tests`) run in the `test` phase, and integration tests (`*IT`, Testcontainers) run in `verify`. Integration tests need Docker running. They start their own disposable MySQL, Redis, RabbitMQ and MailHog containers and never touch the Compose data. One module only: `.\mvnw.cmd verify -pl services/gateway -am`.

### End-to-end check

`scripts/e2e.ps1` starts the infrastructure, generates the development key if needed, builds and starts auth-service, invoice-service and the gateway, then runs `scripts/e2e/E2eSmokeTest.java` against them:

```powershell
.\scripts\e2e.ps1                       # default ports 9000 / 8081 / 8080
.\scripts\e2e.ps1 -GatewayPort 18080    # if 8080 is taken; -SkipBuild reuses existing jars
```

It registers two sellers and a financier with a random password, signs in through the real login form (CSRF token included) with Authorization Code + PKCE, creates and reads an invoice through the gateway, and checks that:
- another seller gets 404;
- a seller gets 403 on admin operations;
- a pending financier cannot sign in;
- PKCE is enforced;
- missing, garbage, tampered and `alg=none` tokens are rejected by the gateway **and** by invoice-service directly.

Service logs are written to `target/e2e/` and scanned for tokens, private keys and the configured passwords; the run fails if anything is found. Logs are deleted after a clean run. Only the processes the script started are stopped.

### Full backend flow on disposable infrastructure

`scripts/e2e-full.ps1` needs no `.env`. It starts its own MySQL, RabbitMQ, Redis and MailHog from `infra/e2e/compose.e2e.yml` under a unique project name, with random passwords, ephemeral ports and data in tmpfs. It then starts all five services and the gateway on free ports, with a throwaway signing key and bootstrap admin, and runs `scripts/e2e/E2eFullFlowTest.java`:

```powershell
.\scripts\e2e-full.ps1              # -SkipBuild reuses existing jars; -KeepInfrastructure leaves the containers up
```

The test registers a seller and a financier and has the admin activate the financier. It then walks an invoice through submission, buyer confirmation, verification and listing, and a financing deal through offer, acceptance, approval, simulated disbursement (including an idempotent replay), a partial and a final repayment, and settlement. It checks that:
- the listed invoice reaches financing-service, and disbursement and settlement move the invoice to `FINANCED` and `SETTLED`;
- each party receives exactly the expected emails in MailHog, none of them containing amounts;
- the audit trail holds every invoice, offer and agreement event, with the request's correlation ID and without email addresses, and is closed to non-admins;
- every dead-letter queue and the unrouted queue are empty at the end.

The development stack and its volumes are never touched, and the disposable containers are removed afterwards.

## Running the services locally

### 1. Signing key (development only)

```powershell
.\scripts\generate-dev-jwt-key.ps1     # macOS/Linux: ./scripts/generate-dev-jwt-key.sh
```

This writes an RSA-3072 PKCS#8 key to `infra/keys/jwt-signing-key.pem` (git-ignored, readable only by you). It never overwrites an existing key. Never commit it and never use it outside your machine; production keys belong in a secret store. auth-service refuses to start without a key unless the `dev` profile is active, which falls back to an in-memory key that changes on every restart (all tokens become invalid).

### 2. Start the services and the gateway

Build once, then start each service in its own terminal from the repository root. Define `Get-DotEnv` in each terminal; it reads a value from `.env` without printing it. Every service except the gateway also needs the RabbitMQ credentials.

```powershell
.\mvnw.cmd -B package -DskipTests
function Get-DotEnv($name) { (Get-Content .env | Select-String "^$name=").Line.Split('=', 2)[1] }
# In each service terminal (not needed for the gateway):
$env:RABBITMQ_USER = Get-DotEnv RABBITMQ_USER; $env:RABBITMQ_PASSWORD = Get-DotEnv RABBITMQ_PASSWORD

# Terminal 1: auth-service on :9000
$env:DB_PASSWORD = Get-DotEnv AUTH_DB_PASSWORD
$env:AUTH_JWT_PRIVATE_KEY_LOCATION = 'file:./infra/keys/jwt-signing-key.pem'
java -jar services/auth-service/target/auth-service-0.1.0-SNAPSHOT.jar

# Terminal 2: invoice-service on :8081 (dev profile adds DEMO- seed invoices)
$env:DB_PASSWORD = Get-DotEnv INVOICE_DB_PASSWORD
$env:SPRING_PROFILES_ACTIVE = 'dev'
java -jar services/invoice-service/target/invoice-service-0.1.0-SNAPSHOT.jar

# Terminal 3: financing-service on :8082
$env:DB_PASSWORD = Get-DotEnv FINANCING_DB_PASSWORD
java -jar services/financing-service/target/financing-service-0.1.0-SNAPSHOT.jar

# Terminal 4: notification-service on :8083 (mail goes to MailHog, http://localhost:8025)
$env:DB_PASSWORD = Get-DotEnv NOTIFICATION_DB_PASSWORD
java -jar services/notification-service/target/notification-service-0.1.0-SNAPSHOT.jar

# Terminal 5: audit-service on :8084 (Flyway uses the migrator user, the app the append-only user)
$env:DB_PASSWORD = Get-DotEnv AUDIT_DB_PASSWORD
$env:DB_MIGRATOR_PASSWORD = Get-DotEnv AUDIT_DB_MIGRATOR_PASSWORD
java -jar services/audit-service/target/audit-service-0.1.0-SNAPSHOT.jar

# Terminal 6: gateway on :8080
$env:REDIS_PASSWORD = Get-DotEnv REDIS_PASSWORD
java -jar services/gateway/target/gateway-0.1.0-SNAPSHOT.jar
```

The dev seed invoices predate the event pipeline and are never published, so they do not appear in financing opportunities. Invoices you create and list through the API do. Inspect queues, including each `<queue>.dlq`, at http://localhost:15672.

| Variable | Default | Used by | Purpose |
|---|---|---|---|
| `AUTH_ISSUER_URI` | `http://localhost:9000` | all | Issuer that tokens carry and services require |
| `AUTH_JWK_SET_URI` | `http://localhost:9000/oauth2/jwks` | gateway, invoice, financing, notification, audit | Where signing keys are fetched |
| `AUTH_AUDIENCE` | `ifp-api` | all | Required `aud` value |
| `AUTH_JWT_PRIVATE_KEY_LOCATION` | (none) | auth | PKCS#8 PEM signing key |
| `AUTH_SPA_REDIRECT_URIS` / `AUTH_SPA_ALLOWED_ORIGINS` | `http://localhost:4200/...` | auth | Registered SPA callback and origins |
| `ADMIN_BOOTSTRAP_EMAIL` / `ADMIN_BOOTSTRAP_PASSWORD` | (none) | auth | Optional first ADMIN, created only if none exists |
| `DB_HOST` / `DB_PORT` / `DB_PASSWORD` | `localhost` / `3307` / (none) | all except gateway | Each service's own database user |
| `DB_MIGRATOR_USERNAME` / `DB_MIGRATOR_PASSWORD` | `audit_migrator` / (none) | audit | Schema owner used only by Flyway |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `localhost` / `5672` / (none) / (none) | all except gateway | Event broker |
| `MAIL_HOST` / `MAIL_PORT` / `NOTIFICATION_FROM` | `localhost` / `1025` / `no-reply@ifp.local` | notification | Local SMTP sink (MailHog) and sender address |
| `AUTH_SERVICE_URI` / `INVOICE_SERVICE_URI` / `FINANCING_SERVICE_URI` / `NOTIFICATION_SERVICE_URI` / `AUDIT_SERVICE_URI` | `http://localhost:9000` / `:8081` / `:8082` / `:8083` / `:8084` | gateway | Route targets |
| `FINANCING_MAX_ADVANCE_RATIO` / `FINANCING_MAX_ANNUAL_RATE` / `FINANCING_MAX_OFFER_VALIDITY` / `FINANCING_MIN_DAYS_TO_MATURITY` / `FINANCING_MAX_INSTALLMENTS` | `0.98` / `0.3600` / `14d` / `7` / `12` | financing | Bounds on offer terms (financiers always set the actual terms) |
| `FINANCING_REPAYMENT_REMINDER_DAYS` / `FINANCING_REPAYMENT_REMINDER_SWEEP_INTERVAL` | `3` / `15m` | financing | "Due soon" window and how often reminders are checked |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / (none) | gateway | Rate-limit store |
| `GATEWAY_CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | gateway | Explicit origins (`*` is rejected at startup) |
| `GATEWAY_TRUSTED_PROXY_HOPS` | `0` | gateway | Trusted `X-Forwarded-For` hops |
| `RATE_LIMIT_PUBLIC_*` / `RATE_LIMIT_API_*` | see `.env.example` | gateway | Token-bucket sizes and failure modes |

### 3. Development users

There are no built-in users or default passwords.

- **SELLER / FINANCIER:** register through the gateway. Financiers start `PENDING_APPROVAL` and cannot sign in until an admin activates them.

  ```powershell
  $body = @{ email = 'seller@example.test'; password = '<choose 12+ characters>'; displayName = 'Demo Seller'; role = 'SELLER' } | ConvertTo-Json
  Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/register -ContentType 'application/json' -Body $body
  ```
- **ADMIN:** cannot self-register. Set `ADMIN_BOOTSTRAP_EMAIL` and `ADMIN_BOOTSTRAP_PASSWORD` (your own values, never committed) before starting auth-service once; it creates the admin only if none exists. The admin activates financiers with `POST /api/admin/users/{id}/activate`.

### 4. Login flow

The SPA (Phase 6) is the public OAuth2 client `ifp-spa`. It sends the browser to `http://localhost:9000/oauth2/authorize` with an S256 PKCE challenge. The user signs in on auth-service's own login page, and the SPA exchanges the returned code plus its PKCE verifier at `/oauth2/token`. Requests without PKCE are refused, and no refresh tokens are issued to the public client. The resulting access token (15 minutes) is sent as `Authorization: Bearer ...` to `http://localhost:8080/api/**`. Discovery is at `http://localhost:9000/.well-known/openid-configuration`, and keys are at `/oauth2/jwks`. These stay on auth-service (the issuer) and are not routed through the gateway.

`GET /actuator/health` is public on every service. Everything else on the gateway requires a valid token, except `POST /api/auth/register`.

The `dev` profile seed is demo data only: fictional sellers and buyers, with invoice numbers prefixed `DEMO-`. Production migrations live in `db/migration`, and the seed lives in `db/seed-dev`.

## Logging

Logs go to stdout. Set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` to emit structured JSON (used in containers). Every request carries an `X-Correlation-Id`, which appears in logs and response headers. A malformed incoming ID is replaced with a fresh UUID rather than forwarded.

## Troubleshooting

- **`ports are not available ... 3306`**: another MySQL is running. Change `MYSQL_PORT` in `.env`.
- **MySQL init script fails with `/bin/bash^M`**: the script has Windows line endings. `.gitattributes` forces LF. If it happened anyway, convert the file, then `docker compose down`, `docker volume rm invoice-financing_mysql-data` (deletes local MySQL data) and start again. Init scripts only run on an empty volume.
- **auth-service: "No JWT signing key configured"**: generate the key (step 1) and set `AUTH_JWT_PRIVATE_KEY_LOCATION`, or use `SPRING_PROFILES_ACTIVE=dev` for a throwaway in-memory key.
- **401 for every token**: issuer mismatch. `AUTH_ISSUER_URI` must be identical (scheme, host, port) for auth-service, the gateway and invoice-service. Tokens issued before an auth-service restart with an ephemeral key are no longer valid.
- **invoice-service: "Detected applied migration not resolved locally: dev demo invoices"**: the local database was migrated with the `dev` profile. Start it with `SPRING_PROFILES_ACTIVE=dev` again.
- **429 on registration**: the public route allows 5 requests per client address per burst (one more every 12 s). Authenticated APIs are limited per user.
- **e2e.ps1: "Port 8080 is already in use"**: another application holds the port. Pass `-GatewayPort` (or `-AuthPort` / `-InvoicePort`).
- **Login page redirects back with `?error`**: wrong password, or a financier still pending approval.
