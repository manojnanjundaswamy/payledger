#!/usr/bin/env bash
# End-to-end check of the running stack: money moves in the ledger, and the resulting events
# reach notification-service through the outbox and Kafka.
#
#   docker compose --profile app up -d --build && scripts/smoke-test.sh
#
# Override LEDGER_URL / NOTIFICATION_URL to point at services running elsewhere (e.g. from your IDE).
set -euo pipefail

LEDGER_URL=${LEDGER_URL:-http://localhost:8080}
NOTIFICATION_URL=${NOTIFICATION_URL:-http://localhost:8090}
run=$(date +%s)-$RANDOM

json_field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1; }

wait_for() {
  local name=$1 url=$2
  for _ in $(seq 1 60); do
    curl -fsS "$url" >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "FAIL: $name not reachable at $url" >&2
  exit 1
}

wait_for ledger-service "${LEDGER_URL%:*}:${LEDGER_MGMT_PORT:-8081}/actuator/health/readiness"
wait_for notification-service "${NOTIFICATION_URL%:*}:${NOTIFICATION_MGMT_PORT:-8091}/actuator/health/readiness"

alice_owner="alice-$run"
bob_owner="bob-$run"
alice=$(curl -fsS -X POST "$LEDGER_URL/api/v1/accounts" -H 'Content-Type: application/json' \
  -d "{\"ownerId\":\"$alice_owner\",\"currency\":\"USD\"}" | json_field id)
bob=$(curl -fsS -X POST "$LEDGER_URL/api/v1/accounts" -H 'Content-Type: application/json' \
  -d "{\"ownerId\":\"$bob_owner\",\"currency\":\"USD\"}" | json_field id)
echo "opened wallets: $alice_owner=$alice $bob_owner=$bob"

curl -fsS -o /dev/null -X POST "$LEDGER_URL/api/v1/deposits" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: smoke-dep-$run" -d "{\"accountId\":\"$alice\",\"amount\":10000,\"currency\":\"USD\"}"
for attempt in 1 2; do  # the retry must not move money twice, nor notify twice
  curl -fsS -o /dev/null -X POST "$LEDGER_URL/api/v1/transfers" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: smoke-tr-$run" \
    -d "{\"fromAccountId\":\"$alice\",\"toAccountId\":\"$bob\",\"amount\":2500,\"currency\":\"USD\",\"description\":\"smoke test\"}"
done
echo "deposited 100.00 USD to $alice_owner, transferred 25.00 USD to $bob_owner (sent twice, same key)"

for _ in $(seq 1 30); do
  feed=$(curl -fsS "$NOTIFICATION_URL/api/v1/notifications?ownerId=$bob_owner")
  if grep -q "You received 25.00 USD from $alice_owner" <<<"$feed"; then
    alice_feed=$(curl -fsS "$NOTIFICATION_URL/api/v1/notifications?ownerId=$alice_owner")
    count=$(grep -o '"message"' <<<"$alice_feed" | wc -l)
    if [ "$count" -ne 2 ]; then
      echo "FAIL: expected 2 notifications for $alice_owner (deposit + transfer), got $count" >&2
      echo "$alice_feed" >&2
      exit 1
    fi
    echo "PASS: events flowed ledger -> outbox -> Kafka -> notification-service"
    echo "$alice_feed"
    exit 0
  fi
  sleep 1
done
echo "FAIL: notification for $bob_owner did not arrive within 30s" >&2
exit 1
