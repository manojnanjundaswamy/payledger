#!/usr/bin/env bash
# Gives the demo account (demo@payledger.local) three months of realistic sample data, through the API: a salary,
# rent, bills, groceries, eating out, transport, a card paid off monthly, a SIP, budgets, a plan, two goals, rules and
# a flat-share group (web/e2e/seed.spec.ts). Everything is tagged "sample"; running it again does nothing.
# The stack must be running (docker compose --profile web up -d). Local only.
set -euo pipefail
cd "$(dirname "$0")/../web"
SEED=1 npx playwright test seed.spec.ts --project=desktop --reporter=line
