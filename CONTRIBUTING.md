# Contributing

## Branching model

- `main` is stable and tested. Nothing is committed to it directly.
- `develop` carries ongoing feature work.
- Create feature branches from `develop` (`feat/<topic>`, `fix/<topic>`, `docs/<topic>`) and open a pull request back into `develop`. CI must pass before merging.
- `develop` is merged into `main` once the full stack has been verified.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<optional scope>): <summary in the imperative, lower case>
```

Types: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci`, `chore`. Scopes are a service or area, for example `feat(financing): ...`, `fix(frontend): ...`, `ci: ...`. Keep each commit to one logical change.

## Running the tests

Backend (needs Docker running for the Testcontainers integration tests):

```bash
./mvnw verify                                   # Windows: .\mvnw.cmd verify
./mvnw verify -pl services/gateway -am          # one module
```

Frontend:

```powershell
. .\scripts\use-local-node.ps1
cd frontend
npm ci
npm run lint
npm run test:ci        # unit tests
npm run build
npm run e2e:ui         # Playwright with a mocked backend
```

End to end against the running services:

```powershell
.\scripts\e2e.ps1          # smoke test: auth-service, invoice-service and the gateway
.\scripts\e2e-full.ps1     # full business flow on disposable infrastructure
```

See the [README](README.md) for the live Playwright specs and for running the whole stack with Docker Compose.

Never commit `.env`, signing keys (`infra/keys/`) or real credentials. `.env.example` holds placeholders only.
