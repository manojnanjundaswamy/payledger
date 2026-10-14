#!/usr/bin/env bash
# Clears test data from the LOCAL stack, keeping one account (the demo user, an admin, by default):
#   1. the browser tests' "E2E …" transactions, bills and opening balances in that account, reversed through the API
#      so the ledger stays right (web/e2e/cleanup.spec.ts);
#   2. the tests' users (…@example.com), with all they own, and the tests' groups, accounts and categories
#      (scripts/clean-test-data.sql); people who really signed up stay;
#   3. those users in Keycloak, so they can't sign in.
# EVERYONE=yes removes every user but KEEP instead (a fresh start).
# The stack must be running (docker compose --profile web up -d). Never point this at production.
#
#   scripts/clean-test-data.sh                    # keeps demo@payledger.local
#   KEEP=you@example.com scripts/clean-test-data.sh
set -euo pipefail
cd "$(dirname "$0")/.."
KEEP=${KEEP:-demo@payledger.local}
EVERYONE=${EVERYONE:-no}
export MSYS_NO_PATHCONV=1

echo "1/3 reversing the E2E entries in $KEEP"
if [ "$KEEP" = "demo@payledger.local" ]; then
  (cd web && CLEANUP=1 npx playwright test cleanup.spec.ts --project=desktop --reporter=line)
else
  echo "   skipped: the API step signs in as the demo user"
fi

echo "2/3 removing test users and leftovers from the database"
docker compose exec -T core-db psql -U core -d core -v ON_ERROR_STOP=1 -v keep="$KEEP" -v everyone="$EVERYONE" -q -f - < scripts/clean-test-data.sql

echo "3/3 removing test users from Keycloak"
kc() { docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"; }
kc config credentials --server http://localhost:8080 --realm master --user "${KC_ADMIN:-admin}" --password "${KC_ADMIN_PASSWORD:-admin}" >/dev/null
removed=0
while IFS=$'\t' read -r id username email; do
  [ -z "$id" ] && continue
  case "$username" in service-account-*) continue ;; esac
  lower=$(echo "$email" | tr '[:upper:]' '[:lower:]')
  [ "$EVERYONE" != "yes" ] && case "$lower" in *@example.com) ;; *) continue ;; esac
  if [ "$lower" != "$(echo "$KEEP" | tr '[:upper:]' '[:lower:]')" ]; then
    # </dev/null: docker exec would otherwise read the rest of the list as its own input.
    kc delete "users/$id" -r payledger >/dev/null </dev/null
    removed=$((removed + 1))
  fi
done < <(kc get users -r payledger --limit 10000 --fields id,username,email --format csv --noquotes | tr ',' '\t' | tr -d '\r')
echo "   $removed Keycloak users removed; $KEEP stays"
