#!/usr/bin/env bash
# End-to-end check of money through the whole stack, the way the web app does it:
# browser session -> gateway -> core-service -> outbox -> Kafka ledger.commands.v1 -> ledger-service,
# and balances read back through core-service.
#
#   docker compose --profile web up -d --build && scripts/money-smoke-test.sh
#
# Adds an account and an expense, edits the expense (reversal + new version), deletes it,
# and after each step waits for the ledger to catch up (it applies commands asynchronously).
set -euo pipefail

GATEWAY=${GATEWAY_URL:-http://localhost:8000}
USERNAME=${LOGIN_USERNAME:-demo@payledger.local}
PASSWORD=${LOGIN_PASSWORD:-demo-password}

jar=$(mktemp)
trap 'rm -f "$jar"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
xsrf() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$jar" | tail -1; }
api() { curl -fsS -c "$jar" -b "$jar" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(xsrf)" "$@"; }
field() { grep -o "\"$1\":\"\?[^\",}]*" | head -1 | sed "s/\"$1\":\"\?//"; }

# ---- sign in through Keycloak's form, like a browser
curl -fsS -o /dev/null -c "$jar" -b "$jar" "$GATEWAY/api/session"
authorize=$(curl -fsS -o /dev/null -w '%{redirect_url}' -c "$jar" -b "$jar" "$GATEWAY/oauth2/authorization/keycloak")
form=$(curl -fsS -c "$jar" -b "$jar" "$authorize")
action=$(grep -o 'id="kc-form-login"[^>]*action="[^"]*"' <<<"$form" | sed 's/.*action="\([^"]*\)".*/\1/; s/&amp;/\&/g')
callback=$(curl -fsS -o /dev/null -w '%{redirect_url}' -c "$jar" -b "$jar" \
  --data-urlencode "username=$USERNAME" --data-urlencode "password=$PASSWORD" "$action")
curl -fsS -o /dev/null -c "$jar" -b "$jar" "$callback"
curl -fsS -o /dev/null -c "$jar" -b "$jar" "$GATEWAY/api/session"

space=$(api "$GATEWAY/api/core/me" | grep -o '"spaces":\[{"id":"[^"]*' | sed 's/.*"id":"//')
[ -n "$space" ] || fail "no personal space"
base="$GATEWAY/api/core/spaces/$space"
category=$(api "$base/categories" | grep -o '{"id":"[^"]*","kind":"EXPENSE","name":"Groceries"' | field id)
echo "signed in as $USERNAME; space $space"

# Waits until the account's balance (from the ledger, via core-service) equals the expected paise.
expect_balance() {
  local account=$1 expected=$2 what=$3 actual=""
  for _ in $(seq 1 30); do
    actual=$(api "$base/accounts/$account" | field balance)
    [ "$actual" = "$expected" ] && { echo "$what: balance $expected"; return; }
    sleep 0.5
  done
  fail "$what: expected balance $expected, got ${actual:-none}"
}

name="Smoke bank $(date +%s)"
account=$(api -X POST -d "{\"kind\":\"BANK\",\"name\":\"$name\",\"openingBalance\":5000000}" "$base/accounts" | field id)
expect_balance "$account" 5000000 "opened with ₹50,000"

txn=$(api -X POST -d "{\"kind\":\"EXPENSE\",\"amount\":48600,\"accountId\":\"$account\",\"categoryId\":\"$category\",
  \"occurredOn\":\"$(date +%F)\",\"description\":\"BigBasket\"}" "$base/transactions" | field id)
expect_balance "$account" 4951400 "spent ₹486"

api -X PUT -d "{\"version\":1,\"kind\":\"EXPENSE\",\"amount\":120000,\"accountId\":\"$account\",\"categoryId\":\"$category\",
  \"occurredOn\":\"$(date +%F)\",\"description\":\"BigBasket\"}" "$base/transactions/$txn" >/dev/null
expect_balance "$account" 4880000 "edited to ₹1,200 (reversal + new version)"

api -X DELETE "$base/transactions/$txn?version=2" >/dev/null
expect_balance "$account" 5000000 "deleted (reversed)"

api -X PATCH -d '{"archived":true}' "$base/accounts/$account" >/dev/null
echo "PASS: accounts and transactions reach the ledger through Kafka, edits and deletes reverse, balances come back"
