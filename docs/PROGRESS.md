# Progress

A portfolio-quality simulation of invoice financing: sellers submit invoices, administrators verify them, financiers compete with offers, and financing-service runs agreements, simulated disbursement, repayment schedules, penalty interest and settlement. The backend is six Spring Boot services (auth, gateway, invoice, financing, notification, audit) that exchange events over RabbitMQ through a transactional outbox. The frontend is an Angular 21 app for sellers, financiers and administrators. Everything runs locally under Docker Compose, and GitHub Actions builds, tests and scans it. See [architecture.md](architecture.md) for the design and the roadmap.

## Finished milestones

| Milestone | Commit on `main` | Notes |
|---|---|---|
| Seller frontend (sign-in with PKCE, registration, seller invoices) | `e6628bb` | Earlier frontend commits: `9214a61` scaffold, `11a549a` sign-in, `b75324e` registration |
| Admin console and notifications | `d082865` | Merge of `feat/admin-notifications` |
| Containers and CI | `0fc8dcd` | Merge of `feat/containerization-ci` |
| Penalty interest, processed-event retention, delayed retry queues | `55a115b` | Merge of `feat/financing-penalty-retention-retry` |
| Financier workflow | `c0ca040` | Merge of `feat/financier-ui` |
| Full-stack run of `main` | `c0ca040` | Smoke test 25/25, business flow 41/41, log scan clean. Run before the seller offers merge |
| Seller offers, agreement, repayments, draft edit and cancel | `ea2a657` | Merge of `feat/seller-offers-repayments` (its merge of `main` is `24eb812`) |
| Configurable live auth URL (`IFP_AUTH_URL`) for the live Playwright specs | `9b85a6f` | |
| Backend gaps: financier rejection, notification read state, delayed retries, `processed_at` index | `4246313` | Branch `feat/backend-gaps` |
| Frontend follow-up: admin reject, server unread count and mark as read, financier penalty breakdown | `c6b867c` | Branch `feat/frontend-follow-up` |
| Deployable images: critical CVEs patched, images published to `ghcr.io/<owner>/ifp/<service>` | after `c6b867c` | Local Trivy gate clean for all 7 images; full stack smoke 25/25, business flow 41/41, live Playwright 9 passed (5 mobile skips by design), log scan clean |

## Queue

1. Spring Boot 4 migration (removes the Spring Framework entries in `.trivyignore`).
2. Observability.
3. Ledger.
4. Risk scoring.
5. AI extraction.
6. Load tests.
7. Hosted deployment (a cloud target that runs the published images).
8. Failure drill and blog post.

## Known issues

- Three critical Spring Framework 6.2.19 CVEs (CVE-2026-47884, -47890, -47892) are accepted in `.trivyignore` until 31 January 2027: Trivy lists a fix only in Spring Framework 7, which needs Spring Boot 4.
- New GHCR packages are private until made public in the package settings.
- On a loaded host, the first `docker compose --wait` can report RabbitMQ unhealthy; a second `--wait` passes.
- The penalty rate (0.05% per day) and the 10% cap are placeholders that need business sign-off.
- Trivy runs with `ignore-unfixed`.
- MySQL DDL is not transactional, so a failed Flyway migration can leave partial changes.
- The seller page finds an invoice's agreement only among the seller's 1,000 most recent agreements.
- Repayment idempotency keys are kept in memory only.
