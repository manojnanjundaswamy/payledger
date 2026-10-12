#!/usr/bin/env bash
# End-to-end check of the running stack: entries are recorded in the ledger, an edit nets out
# through a reversal, and every event reaches Kafka through the outbox.
#
#   docker compose --profile app up -d --build && scripts/smoke-test.sh
#
# Override LEDGER_URL / LEDGER_MGMT_URL to point at a ledger running elsewhere (e.g. from your IDE).
set -euo pipefail

LEDGER_URL=${LEDGER_URL:-http://localhost:8080}
LEDGER_MGMT_URL=${LEDGER_MGMT_URL:-http://localhost:8081}
run=$(date +%s)-$RANDOM
book="space:smoke-$run"

fail() { echo "FAIL: $*" >&2; exit 1; }

for _ in $(seq 1 60); do
  curl -fsS "$LEDGER_MGMT_URL/actuator/health/readiness" >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS "$LEDGER_MGMT_URL/actuator/health/readiness" >/dev/null || fail "ledger-service not ready at $LEDGER_MGMT_URL"

# post <idempotency-key> <entryType> <date> <legs-json>
post() {
  curl -fsS -o /dev/null -w '%{http_code}' -X POST "$LEDGER_URL/api/v2/entries" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $1" \
    -d "{\"bookRef\":\"$book\",\"bookKind\":\"PERSONAL\",\"entryType\":\"$2\",\"effectiveDate\":\"$3\",\"legs\":$4}"
}

salary='[{"accountRef":"bank:hdfc","accountType":"ASSET","currency":"INR","amount":8000000},
         {"accountRef":"income:salary","accountType":"INCOME","currency":"INR","amount":-8000000}]'
dinner='[{"accountRef":"category:eating-out","accountType":"EXPENSE","currency":"INR","amount":250000},
         {"accountRef":"card:icici","accountType":"LIABILITY","currency":"INR","amount":-250000}]'
dinner_fixed='[{"accountRef":"category:eating-out","accountType":"EXPENSE","currency":"INR","amount":180000},
               {"accountRef":"card:icici","accountType":"LIABILITY","currency":"INR","amount":-180000}]'

[ "$(post "salary-$run" INCOME 2026-10-01 "$salary")" = 201 ] || fail "salary not recorded"
[ "$(post "dinner-$run-v1" EXPENSE 2026-10-05 "$dinner")" = 201 ] || fail "dinner not recorded"
[ "$(post "dinner-$run-v1" EXPENSE 2026-10-05 "$dinner")" = 200 ] || fail "retry with the same key was not replayed"
echo "recorded salary 80,000.00 and dinner 2,500.00 (dinner sent twice with the same key)"

# Edit the dinner to 1,800.00: reverse v1, post v2.
code=$(curl -fsS -o /dev/null -w '%{http_code}' -X POST "$LEDGER_URL/api/v2/reversals" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: dinner-$run-v1-reversal" \
  -d "{\"reversesIdempotencyKey\":\"dinner-$run-v1\",\"description\":\"amount corrected\"}")
[ "$code" = 201 ] || fail "reversal not recorded"
[ "$(post "dinner-$run-v2" EXPENSE 2026-10-05 "$dinner_fixed")" = 201 ] || fail "corrected dinner not recorded"
echo "edited dinner to 1,800.00 (reversal + new entry)"

balances=$(curl -fsS "$LEDGER_URL/api/v2/books/$book/balances")
grep -q '"accountRef":"card:icici","accountType":"LIABILITY","currency":"INR","balance":-180000' <<<"$balances" \
  || fail "unexpected card balance: $balances"
grep -q '"accountRef":"bank:hdfc","accountType":"ASSET","currency":"INR","balance":8000000' <<<"$balances" \
  || fail "unexpected bank balance: $balances"

totals=$(curl -fsS "$LEDGER_URL/api/v2/books/$book/totals?from=2026-10-01&to=2026-10-31&type=EXPENSE")
grep -q '"accountRef":"category:eating-out".*"total":180000' <<<"$totals" || fail "unexpected October spending: $totals"
echo "balances and October spending are correct"

for _ in $(seq 1 20); do
  pending=$(curl -fsS "$LEDGER_MGMT_URL/actuator/prometheus" | sed -n 's/^payledger_outbox_pending{[^}]*} //p')
  [ "${pending%.*}" = 0 ] && { echo "PASS: all ledger events were published to Kafka"; exit 0; }
  sleep 1
done
fail "outbox still has $pending unpublished events (is Kafka up and OUTBOX_RELAY_ENABLED=true?)"
