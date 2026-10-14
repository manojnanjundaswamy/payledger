# Environments and branches

| | Branch | Docker project | Settings | Address |
|---|---|---|---|---|
| **Production** | `main` | `payledger` | `.env` (from `deploy/env/production.env.example`) | https://payledger.manojnanjundaswamy.com (Cloudflare Tunnel to port 5173) |
| **Staging** | `develop` | `payledger-staging` | `deploy/env/staging.env` (or your own `.env.staging`) | http://localhost:5273 |

Both run on the same machine, side by side, each with its own databases, Keycloak, Kafka and ports (staging's are the
production ones + 100). Stop staging when you don't need it: `scripts/env.sh staging down` (its data is kept).

## Branches

- `feature/…`, `fix/…`: one piece of work, branched from `develop`.
- `develop`: everything finished and tested together; what staging runs.
- `main`: what production runs. A release is `develop` merged into `main`, tagged `vMAJOR.MINOR.PATCH`.
- `hotfix/…`: an urgent production fix, branched from `main`, merged into both `main` and `develop`.

## Day to day

```bash
scripts/env.sh staging up            # try the work checked out here on staging
scripts/env.sh staging e2e           # browser tests against staging (never against production)
scripts/env.sh staging clean         # remove what the tests left behind
scripts/env.sh staging deploy        # staging runs develop exactly as committed

# a release
git checkout main && git merge --no-ff develop && git tag v1.0.0
scripts/env.sh production deploy     # production runs main; sign-in stays on the live address
```

`deploy` builds a clean checkout of the branch in `../payledger-<environment>` (a git worktree), so what runs is
exactly what's on the branch, not whatever is half-edited here. Production keeps its data across deploys.
