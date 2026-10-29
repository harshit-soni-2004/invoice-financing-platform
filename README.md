# Invoice Financing Platform

[![CI](https://github.com/OWNER/REPO/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/OWNER/REPO/actions/workflows/ci.yml) <!-- Replace OWNER/REPO once the GitHub remote exists. -->

A portfolio-quality **simulation** of invoice financing: sellers submit invoices, financiers make offers, accepted offers are disbursed (simulated), and repayments are recorded until settlement.

> No real banking, payment gateway or money movement is involved. All amounts are in INR and handled as `BigDecimal` / `DECIMAL(19,2)`.

**Status:** Phase 5 is complete, and Phase 6 (the Angular app) is in progress: users can register and sign in, and sellers can add, follow and submit their invoices; the admin and financier workflows come next. auth-service issues RS256 tokens through Authorization Code + PKCE, the gateway validates them, routes `/api/**`, applies CORS and Redis rate limits, and each service validates every token again itself. financing-service implements offers from competing financiers, agreements with an explicit state machine, idempotent simulated disbursement, repayment schedules, partial and full repayments, overdue tracking and settlement. Services exchange events over RabbitMQ through a transactional outbox, with idempotent consumers, bounded retries and dead-letter queues. Listed invoices reach financing-service's read model, disbursement and settlement update the invoice, notification-service emails the parties through MailHog, and audit-service keeps an append-only trail of every event. See [docs/architecture.md](docs/architecture.md) for the design, the financial rules and the roadmap.

## Stack

Java 21 · Spring Boot 3.5 · Spring Cloud Gateway 2025.0 · Spring Authorization Server · MySQL 8.4 · Flyway · RabbitMQ 4.1 · Redis 7.4 · MailHog · Angular 21 · Docker Compose · Testcontainers · GitHub Actions

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 21 |
| Docker Desktop | Engine 29+ with Compose v2 |
| Node.js (frontend only) | 22 LTS, project-local: `scripts/use-local-node.ps1` downloads it into the git-ignored `.tools/` (see [Frontend](#frontend)) |

Maven does not need to be installed. Use the bundled wrapper (`mvnw` / `mvnw.cmd`).

## Run everything

Docker is the only requirement. One command builds the six services and the frontend and starts them with the infrastructure:

```bash
cp .env.example .env          # then replace every replace-me-* value
docker compose --profile app up -d --build --wait
```

`--wait` returns once every container reports healthy. The first build downloads all Maven and npm dependencies and takes several minutes; later builds reuse the cached dependency layers.

| Component | Address |
|---|---|
| Frontend | http://localhost:4200 |
| Gateway (`/api/**`) | http://localhost:8080 |
| auth-service (sign-in page, token issuer) | http://localhost:9000 |
| MailHog UI | http://localhost:8025 |

invoice-, financing-, notification- and audit-service are reachable only inside the Compose network, through the gateway.

How it fits together:
- A one-shot `jwt-keygen` container writes a development signing key into the `jwt-keys` volume on first start and keeps it afterwards. auth-service mounts the volume read-only.
- Tokens carry the browser-visible issuer `AUTH_ISSUER_URI` (`http://localhost:9000`). The gateway and the services check that issuer, but fetch the signing keys from `http://auth-service:9000/oauth2/jwks` inside the network. The `*_URI` values in `.env` that point at `localhost` are only used when the services run on the host.
- The frontend image serves the production build with nginx. Its `config.json` is written at container start from `IFP_PUBLIC_API_URL` and `AUTH_ISSUER_URI`.
- Every container runs as a non-root user, and the service containers have read-only root filesystems.

Host ports are set with `AUTH_PORT`, `GATEWAY_PORT` and `FRONTEND_PORT`. The browser talks to these URLs directly, so when you change a port, also change `AUTH_ISSUER_URI`, `IFP_PUBLIC_API_URL`, `AUTH_SPA_*` and `GATEWAY_CORS_ALLOWED_ORIGINS` to match.

Stop with `docker compose --profile app down`. `down -v` also deletes the data and the signing key.

Without `--profile app`, `docker compose up -d` starts only the infrastructure, for running the services from the IDE (below).

### CI

`.github/workflows/ci.yml` runs on every push and pull request:
- `./mvnw verify` with Testcontainers;
- frontend lint, unit tests and production build;
- the mocked Playwright suite;
- the whole stack in Compose. That last job runs `scripts/e2e/E2eSmokeTest.java` and the live Playwright specs against the containers, then tears the stack down.

`.github/workflows/images.yml` builds every image on `main`, fails on critical Trivy findings, uploads an SPDX SBOM per image and pushes to `ghcr.io/<owner>/ifp-<service>`. Dependabot keeps Maven, npm, Docker base images and GitHub Actions current.

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

## Frontend

Angular 21 (LTS) in `frontend/`: standalone components, signals, zoneless change detection, strict TypeScript and plain SCSS with light and dark themes. The phase plan named Angular 19, but 19 is end-of-life and its last release has unpatched high-severity advisories, so the app uses the 21 LTS line instead.

Node is project-local. Dot-sourcing the helper downloads Node 22.23.4 into the git-ignored `.tools/node`, verifies it against the official SHA-256 list, and puts it first on `PATH` for the current PowerShell session only. The system Node installation and npm configuration are not touched.

```powershell
. .\scripts\use-local-node.ps1
cd frontend
npm ci                 # install exactly what package-lock.json pins
npm start              # dev server on http://localhost:4200
npm run lint
npm run test:ci        # Karma + Jasmine in headless Chrome
npm run build          # production build in dist/frontend
npm run e2e:ui         # Playwright UI checks at desktop and phone sizes (uses the installed Chrome)
```

`public/config.json` holds the gateway URL (`apiBaseUrl`), the auth-service issuer (`authIssuer`) and the OAuth2 client ID. It is read at startup rather than compiled in, so the same build can point at any environment. `$env:SCREENSHOTS = '1'; npm run e2e:ui` refreshes the images in `docs/screenshots/`.

**Sign-in.** "Sign in", or any page that needs an account (`/account`, `/seller`, `/financier`, `/admin`), sends the browser to auth-service's login page with Authorization Code + PKCE (see [Login flow](#4-login-flow)). The access token stays in memory, so reloading the page goes back through auth-service. Its session cookie skips the password, so the user only sees a brief redirect. The session ends with the 15-minute access token, after which the app asks the user to sign in again. "Sign out" also ends the auth-service session. The financier and admin pages are still placeholders.

**Seller invoices.** `/seller` lists the signed-in seller's invoices from `GET /api/invoices`, newest first, 20 per page, with a status filter. The browser never names a seller: invoice-service scopes every request to the seller in the access token, and another seller's invoice is a 404. "New invoice" (`/seller/invoices/new`) creates a draft with `POST /api/invoices`. The form checks the same formats as invoice-service: invoice number characters, GSTIN, an amount with at most 2 decimals, and a due date after the issue date. Configurable limits (maximum amount, tenor, days to maturity) are left to invoice-service, whose message is shown next to the field. `/seller/invoices/{id}` shows the details and offers the two seller transitions, each enabled only in the status that allows it: "Submit for verification" for a draft and "Make available to financiers" for a verified invoice. Both send the invoice's `ETag` as `If-Match`. If the invoice changed in the meantime, invoice-service refuses with `412` or `409`, and the page explains why and shows the stored state. The page only shows a new status after invoice-service confirms it. Editing and cancelling drafts are not in the app yet.

**Registration.** `/register` creates a seller or financier account through the gateway's `POST /api/auth/register`. Administrators are never offered. The form checks the same rules as auth-service before sending: a password of 12+ characters, at most 72 UTF-8 bytes, and not the email address. Duplicate emails, server validation errors, rate limiting (`429`, using `Retry-After` when present), network failures and outages each get their own message. Registration does not sign the user in. A seller is pointed to "Sign in", and a financier is told that the account waits for an administrator's approval and that sign-in is refused until then.

`npm run e2e:ui` stands in for auth-service and the gateway inside the browser, so it needs no backend. To check sign-in, registration and the seller's invoices against the real services, start auth-service, invoice-service (with the `dev` profile) and the gateway as described in [Running the services locally](#running-the-services-locally), then run the opt-in checks. They register new sellers and financiers with random passwords, and the invoice check only creates invoices for its own new seller. The app must be served on `localhost`, because auth-service only accepts the registered `http://localhost:4200/auth/callback`:

```powershell
npx ng serve --host localhost              # in a separate terminal
$env:IFP_STACK = '1'; $env:UI_BASE_URL = 'http://localhost:4200'
# $env:IFP_GATEWAY_URL = 'http://localhost:18080'   # only if the gateway is not on :8080
npx playwright test e2e/auth.live.spec.ts
npx playwright test e2e/register.live.spec.ts
npx playwright test e2e/seller-invoices.live.spec.ts
```

The gateway allows 5 registrations per burst per client address, refilling one every 12 seconds. The three files together make 8 registration requests, so run them separately, about a minute apart. The registration and invoice checks run at desktop size only for this reason.

npm 10 (bundled with Node 22) installs reliably from the lockfile, but crashes with `Cannot read properties of null (reading 'edgesOut')` when it resolves this dependency tree from scratch. To add or upgrade a dependency, run `npx -y npm@11 install <package>`, then check the result with `npm ci`.

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

# Terminal 1: auth-service on :9000 (Redis holds the login rate-limit counters)
$env:DB_PASSWORD = Get-DotEnv AUTH_DB_PASSWORD
$env:REDIS_PASSWORD = Get-DotEnv REDIS_PASSWORD
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
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / (none) | gateway, auth | Rate-limit store |
| `AUTH_RATE_LIMIT_IP_REQUESTS` / `AUTH_RATE_LIMIT_IP_WINDOW` | `30` / `1m` | auth | Requests per client address to `POST /login` and to `POST /oauth2/token` |
| `AUTH_RATE_LIMIT_MAX_FAILURES` / `AUTH_RATE_LIMIT_FAILURE_WINDOW` / `AUTH_RATE_LIMIT_LOCKOUT` | `5` / `15m` / `5m` | auth | Failed logins per client address + username before a lockout, and its length |
| `IFP_MESSAGING_PURGE_RETENTION` / `IFP_MESSAGING_PURGE_INTERVAL` | `7d` / `1h` | auth, invoice, financing | How long published outbox rows are kept, and how often they are purged |
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

`GET /actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness` are public on every service. Readiness also requires the service's database. RabbitMQ and Redis are deliberately not part of it, because the outbox and the rate-limit failure modes already handle their outages. Everything else on the gateway requires a valid token, except `POST /api/auth/register`.

The `dev` profile seed is demo data only: fictional sellers and buyers, with invoice numbers prefixed `DEMO-`. Production migrations live in `db/migration`, and the seed lives in `db/seed-dev`.

## Logging

Logs go to stdout as one JSON object per line (Elastic Common Schema), with `service.name` and the `correlationId` of the request or consumed event. For human-readable lines while developing, add the `local` profile (`SPRING_PROFILES_ACTIVE=local`, or `dev,local` for invoice-service), which prints the correlation ID in brackets. Every request carries an `X-Correlation-Id`, which appears in logs and response headers. A malformed incoming ID is replaced with a fresh UUID rather than forwarded. Log statements contain IDs, statuses, event types and exception class names only, never emails, tokens, passwords or amounts.

## Troubleshooting

- **`ports are not available ... 3306`**: another MySQL is running. Change `MYSQL_PORT` in `.env`.
- **MySQL init script fails with `/bin/bash^M`**: the script has Windows line endings. `.gitattributes` forces LF. If it happened anyway, convert the file, then `docker compose down`, `docker volume rm invoice-financing_mysql-data` (deletes local MySQL data) and start again. Init scripts only run on an empty volume.
- **auth-service: "No JWT signing key configured"**: generate the key (step 1) and set `AUTH_JWT_PRIVATE_KEY_LOCATION`, or use `SPRING_PROFILES_ACTIVE=dev` for a throwaway in-memory key.
- **401 for every token**: issuer mismatch. `AUTH_ISSUER_URI` must be identical (scheme, host, port) for auth-service, the gateway and invoice-service. Tokens issued before an auth-service restart with an ephemeral key are no longer valid.
- **invoice-service: "Detected applied migration not resolved locally: dev demo invoices"**: the local database was migrated with the `dev` profile. Start it with `SPRING_PROFILES_ACTIVE=dev` again.
- **429 on registration**: the public route allows 5 requests per client address per burst (one more every 12 s). Authenticated APIs are limited per user.
- **e2e.ps1: "Port 8080 is already in use"**: another application holds the port. Pass `-GatewayPort` (or `-AuthPort` / `-InvoicePort`).
- **Login page redirects back with `?error`**: wrong password, or a financier still pending approval.
- **429 on `/login` or `/oauth2/token`**: five failed logins for the same username from the same address lock that pair out for 5 minutes, and each address may make 30 requests per minute to each endpoint. `Retry-After` says how long to wait.
