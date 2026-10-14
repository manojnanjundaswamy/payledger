#!/usr/bin/env bash
# Clears test data from the LOCAL stack, keeping one account (the demo user, an admin, by default):
#   1. the browser tests' "E2E …" transactions, bills and opening balances in that account, reversed through the API
#      so the ledger stays right (web/e2e/cleanup.spec.ts);
#   2. every other user, with all they own, and the tests' groups, accounts and categories (scripts/clean-test-data.sql);
#   3. every other user in Keycloak, so they can't sign in.
# The stack must be running (docker compose --profile web up -d). Never point this at production.
#
#   scripts/clean-test-data.sh                    # keeps demo@payledger.local
#   KEEP=you@example.com scripts/clean-test-data.sh
set -euo pipefail
cd "$(dirname "$0")/.."
KEEP=${KEEP:-demo@payledger.local}

echo "1/3 reversing the E2E entries in $KEEP"
if [ "$KEEP" = "demo@payledger.local" ]; then
  (cd web && CLEANUP=1 npx playwright test cleanup.spec.ts --project=desktop --reporter=line)
else
  echo "   skipped: the API step signs in as the demo user"
fi

echo "2/3 removing other users and test leftovers from the database"
docker compose exec -T core-db psql -U core -d core -v ON_ERROR_STOP=1 -v keep="$KEEP" -q -f - < scripts/clean-test-data.sql

echo "3/3 removing other users from Keycloak"
kc() { docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://localhost:8080 --realm master --user "${KC_ADMIN:-admin}" --password "${KC_ADMIN_PASSWORD:-admin}" >/dev/null
removed=0
while IFS=$'\t' read -r id username email; do
  [ -z "$id" ] && continue
  case "$username" in service-account-*) continue ;; esac
  if [ "$(echo "$email" | tr '[:upper:]' '[:lower:]')" != "$(echo "$KEEP" | tr '[:upper:]' '[:lower:]')" ]; then
    kc delete "users/$id" -r payledger >/dev/null
    removed=$((removed + 1))
  fi
done < <(kc get users -r payledger --limit 10000 --fields id,username,email --format csv --noquotes | tr ',' '\t' | tr -d '\r')
echo "   $removed Keycloak users removed; $KEEP stays"
