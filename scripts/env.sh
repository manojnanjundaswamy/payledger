#!/usr/bin/env bash
# Runs one environment of PayLedger on this machine. Two can run side by side:
#
#   production  the live site (PUBLIC_URL in .env), Docker project "payledger", built from the main branch
#   staging     for testing, Docker project "payledger-staging", http://localhost:5273, built from develop
#
#   scripts/env.sh <staging|production> deploy    build the environment's branch (a clean checkout) and start it
#   scripts/env.sh staging up                     build what's checked out here (try work before it's merged)
#   scripts/env.sh <env> down|status|logs [svc]   stop, list containers, follow logs
#   scripts/env.sh staging e2e [playwright args]  browser tests against staging (never against production)
#   scripts/env.sh staging clean|seed             remove test data / add sample data, in staging
#
# Data lives in each project's own Docker volumes; "down" keeps it.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$(pwd)
export MSYS_NO_PATHCONV=1

ENV=${1:?staging or production}
CMD=${2:?what to do: deploy, up, down, status, logs, e2e, clean, seed}
shift 2

case "$ENV" in
  production) PROJECT=payledger; ENV_FILE=.env; BRANCH=main ;;
  staging)
    PROJECT=payledger-staging; BRANCH=develop
    # Your own .env.staging if there is one, else the committed defaults.
    if [ -f .env.staging ]; then ENV_FILE=.env.staging; else ENV_FILE=deploy/env/staging.env; fi ;;
  *) echo "unknown environment: $ENV (staging or production)" >&2; exit 2 ;;
esac
[ -f "$ENV_FILE" ] || { echo "$ENV_FILE is missing (see deploy/env/)" >&2; exit 1; }
export COMPOSE_PROJECT_NAME=$PROJECT ENV_FILE
set -a; . "./$ENV_FILE"; set +a
WEB_URL="${PUBLIC_URL:-http://localhost:${WEB_PORT:-5173}}"

compose() { docker compose -p "$PROJECT" --env-file "$REPO/$ENV_FILE" "$@"; }

ready() {
  echo "waiting for $ENV to be ready…"
  for _ in $(seq 1 120); do
    if curl -sf "http://localhost:${CORE_MANAGEMENT_PORT:-8061}/actuator/health/readiness" >/dev/null \
      && curl -sf "http://localhost:${WEB_PORT:-5173}/healthz" >/dev/null \
      && [ "$(compose ps keycloak --format '{{.Health}}')" = healthy ]; then
      ENV_FILE=$ENV_FILE bash scripts/keycloak-sync.sh >/dev/null
      echo "$ENV is up: $WEB_URL"
      return 0
    fi
    sleep 5
  done
  echo "$ENV did not become ready; see: scripts/env.sh $ENV logs" >&2
  return 1
}

case "$CMD" in
  up)
    compose --profile web up -d --build "$@"
    ready ;;
  deploy)
    # A clean checkout of the branch, next to this one, so what runs is exactly what was merged.
    TREE="$REPO/../payledger-$ENV"
    git fetch -q origin "$BRANCH"
    if [ -d "$TREE" ]; then git -C "$TREE" checkout -q --detach "origin/$BRANCH"; else git worktree add -q --detach "$TREE" "origin/$BRANCH"; fi
    echo "deploying $ENV from $BRANCH at $(git -C "$TREE" log --oneline -1)"
    docker compose -p "$PROJECT" --env-file "$REPO/$ENV_FILE" --project-directory "$TREE" -f "$TREE/docker-compose.yml" --profile web up -d --build
    ready ;;
  down) compose --profile app --profile web down ;;
  status) compose ps ;;
  logs) compose logs -f --tail 100 "$@" ;;
  e2e)
    [ "$ENV" = staging ] || { echo "browser tests run against staging only" >&2; exit 2; }
    (cd web && WEB_URL="$WEB_URL" npx playwright test "$@") ;;
  clean)
    [ "$ENV" = staging ] || { echo "clean runs against staging only (production keeps real people's data)" >&2; exit 2; }
    (cd web && WEB_URL="$WEB_URL" CLEANUP=1 npx playwright test cleanup.spec.ts --project=desktop --reporter=line)
    compose exec -T core-db psql -U core -d core -v ON_ERROR_STOP=1 -v keep=demo@payledger.local -v everyone=no -q -f - < scripts/clean-test-data.sql ;;
  seed)
    [ "$ENV" = staging ] || { echo "sample data goes into staging only" >&2; exit 2; }
    (cd web && WEB_URL="$WEB_URL" SEED=1 npx playwright test seed.spec.ts --project=desktop --reporter=line) ;;
  *) echo "unknown command: $CMD" >&2; exit 2 ;;
esac
