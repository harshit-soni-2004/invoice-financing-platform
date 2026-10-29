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
| Configurable live auth URL (`IFP_AUTH_URL`) for the live Playwright specs | `9b85a6f` | Live specs not yet run with it |

## Queue

1. **Backend gaps** (Agent 2, branch `feat/backend-gaps`): financier rejection, notification read state, delayed retries and the `processed_at` index in invoice, notification and audit services.
2. **Frontend follow-up:** admin reject button for financiers, real unread notification count, penalty columns in the financier repayment schedule.
3. **Live Playwright against the container stack**, with `IFP_AUTH_URL` set.
4. Observability.
5. Ledger.
6. Risk scoring.
7. AI extraction.
8. Load tests.
9. Deployment.
10. Failure drill and blog post.

## Known issues

- The CI badge in the README still uses the `OWNER/REPO` placeholder.
- The live Playwright specs have not passed against the containers yet.
- On a loaded host, the first `docker compose --wait` can report RabbitMQ unhealthy; a second `--wait` passes.
- The penalty rate (0.05% per day) and the 10% cap are placeholders that need business sign-off.
- Trivy runs with `ignore-unfixed`.
- MySQL DDL is not transactional, so a failed Flyway migration can leave partial changes.
- The seller page finds an invoice's agreement only among the seller's 1,000 most recent agreements.
- Repayment idempotency keys are kept in memory only.
- The financier UI does not show the penalty breakdown yet.
