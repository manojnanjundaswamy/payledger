#!/usr/bin/env bash
# End-to-end check of sign-in, done the way a browser does it: gateway -> Keycloak login form ->
# redirect back -> session cookie -> API calls relayed to core-service with the user's token.
#
#   docker compose --profile gateway --profile core up -d --build && scripts/login-smoke-test.sh
#
# Part 1 signs in as the demo user imported with the realm (deploy/local/keycloak/payledger-realm.json).
# Part 2 signs up a brand-new user and verifies their email through Mailpit.
set -euo pipefail

GATEWAY=${GATEWAY_URL:-http://localhost:8000}
MAILPIT=${MAILPIT_URL:-http://localhost:8025}
USERNAME=${LOGIN_USERNAME:-demo@payledger.local}
PASSWORD=${LOGIN_PASSWORD:-demo-password}

jar=$(mktemp)
trap 'rm -f "$jar"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
xsrf() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$jar" | tail -1; }
# Follow redirects like a browser until we are back at the web app; prints the final URL.
follow() {
  local url=$1
  for _ in $(seq 1 10); do
    case "$url" in http://localhost:5173*) echo "$url"; return ;; esac
    url=$(curl -fsS -o /dev/null -w '%{redirect_url}' -c "$jar" -b "$jar" "$url")
    [ -n "$url" ] || fail "redirect chain ended before reaching the web app"
  done
  fail "too many redirects"
}

for _ in $(seq 1 60); do
  curl -fsS "${GATEWAY%:*}:${GATEWAY_MGMT_PORT:-8001}/actuator/health/readiness" >/dev/null 2>&1 && break
  sleep 2
done

session=$(curl -fsS -c "$jar" -b "$jar" "$GATEWAY/api/session")
grep -q '"authenticated":false' <<<"$session" || fail "expected an anonymous session: $session"
[ "$(curl -s -o /dev/null -w '%{http_code}' -b "$jar" "$GATEWAY/api/core/me")" = 401 ] || fail "API without login must be 401"
echo "anonymous: session says signed out, API answers 401"

# 1. The gateway sends the browser to Keycloak (with PKCE).
authorize=$(curl -fsS -o /dev/null -w '%{redirect_url}' -c "$jar" -b "$jar" "$GATEWAY/oauth2/authorization/keycloak")
grep -q 'code_challenge=' <<<"$authorize" || fail "authorization request without PKCE: $authorize"

# 2. Keycloak shows its login form; submit the credentials.
form=$(curl -fsS -c "$jar" -b "$jar" "$authorize")
action=$(grep -o 'id="kc-form-login"[^>]*action="[^"]*"' <<<"$form" | sed 's/.*action="\([^"]*\)".*/\1/; s/&amp;/\&/g')
[ -n "$action" ] || fail "no Keycloak login form found"
callback=$(curl -fsS -o /dev/null -w '%{redirect_url}' -c "$jar" -b "$jar" \
  --data-urlencode "username=$USERNAME" --data-urlencode "password=$PASSWORD" "$action")
grep -q '/login/oauth2/code/keycloak?' <<<"$callback" || fail "Keycloak did not redirect back with a code: $callback"

# 3. Back at the gateway: it exchanges the code for tokens and starts the session.
landing=$(curl -fsS -o /dev/null -w '%{redirect_url}' -c "$jar" -b "$jar" "$callback")
grep -q '^http://localhost:5173' <<<"$landing" || fail "expected a redirect to the web app, got: $landing"
grep -q 'PAYLEDGER_SESSION' "$jar" || fail "no session cookie"
echo "signed in as $USERNAME through Keycloak; redirected to $landing"

session=$(curl -fsS -c "$jar" -b "$jar" "$GATEWAY/api/session")
grep -q '"authenticated":true' <<<"$session" || fail "session not authenticated: $session"

me=$(curl -fsS -c "$jar" -b "$jar" "$GATEWAY/api/core/me")
grep -q "\"email\":\"$USERNAME\"" <<<"$me" || fail "unexpected profile: $me"
grep -q '"kind":"PERSONAL"' <<<"$me" || fail "no personal space: $me"
echo "core-service profile via gateway: $me"

# 4. Writes need the CSRF token, like the web app will send it.
[ "$(curl -s -o /dev/null -w '%{http_code}' -b "$jar" -X PATCH -H 'Content-Type: application/json' \
  -d '{"budgetStartDay":25}' "$GATEWAY/api/core/me")" = 403 ] || fail "PATCH without CSRF header must be 403"
updated=$(curl -fsS -c "$jar" -b "$jar" -X PATCH -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(xsrf)" \
  -d '{"budgetStartDay":25,"preferences":{"theme":"dark"}}' "$GATEWAY/api/core/me")
grep -q '"budgetStartDay":25' <<<"$updated" || fail "update failed: $updated"
echo "updated settings through the gateway (CSRF enforced)"

# 4b. Sessions and the user's tokens live in Redis: restarting the gateway must not break a signed-in session.
if [ "${SKIP_GATEWAY_RESTART:-0}" != 1 ]; then
  docker compose restart gateway >/dev/null 2>&1
  for _ in $(seq 1 60); do curl -fsS -o /dev/null "$GATEWAY/api/session" 2>/dev/null && break; sleep 2; done
  status=$(curl -s -o /dev/null -w '%{http_code}' -c "$jar" -b "$jar" "$GATEWAY/api/core/me")
  [ "$status" = 200 ] || fail "after a gateway restart the API answered $status instead of 200 (tokens lost?)"
  echo "gateway restarted: still signed in, API calls still work"
fi

# 5. Logout ends the gateway session and points the browser at Keycloak's logout.
logout=$(curl -fsS -c "$jar" -b "$jar" -X POST -H "X-XSRF-TOKEN: $(xsrf)" "$GATEWAY/logout")
grep -q 'protocol/openid-connect/logout' <<<"$logout" || fail "unexpected logout response: $logout"
session=$(curl -fsS -c "$jar" -b "$jar" "$GATEWAY/api/session")
grep -q '"authenticated":false' <<<"$session" || fail "still signed in after logout: $session"
echo "signed out"

# ---------------------------------------------------------------- part 2: sign up
rm -f "$jar"
email="new-$(date +%s)-$RANDOM@example.com"
register=$(curl -fsS -o /dev/null -w '%{redirect_url}' -c "$jar" -b "$jar" "$GATEWAY/oauth2/authorization/keycloak?signup")
form=$(curl -fsS -c "$jar" -b "$jar" "$register")
action=$(grep -o 'id="kc-register-form"[^>]*action="[^"]*"' <<<"$form" | sed 's/.*action="\([^"]*\)".*/\1/; s/&amp;/\&/g')
[ -n "$action" ] || fail "the signup link did not open Keycloak's registration form"
verify_page=$(curl -fsS -o /dev/null -w '%{redirect_url}' -c "$jar" -b "$jar" --data-urlencode "email=$email" \
  --data-urlencode "firstName=Neha" --data-urlencode "lastName=Rao" \
  --data-urlencode "password=correct-horse-9" --data-urlencode "password-confirm=correct-horse-9" "$action")
grep -q 'VERIFY_EMAIL' <<<"$verify_page" || fail "registration did not ask for email verification: $verify_page"
curl -fsS -o /dev/null -c "$jar" -b "$jar" "$verify_page" # renders "check your inbox" and sends the email

link=""
for _ in $(seq 1 15); do
  link=$(curl -fsS "$MAILPIT/api/v1/search?query=to:$email" | python3 -c '
import json, re, sys, urllib.request
messages = json.load(sys.stdin)["messages"]
if messages:
    text = json.load(urllib.request.urlopen(sys.argv[1] + "/api/v1/message/" + messages[0]["ID"]))["Text"]
    print(re.search(r"http://localhost:8180/\S+action-token\S+", text).group(0))
' "$MAILPIT")
  [ -n "$link" ] && break
  sleep 1
done
[ -n "$link" ] || fail "no verification email for $email in Mailpit"
echo "registered $email; verification email arrived in Mailpit"

follow "$link" >/dev/null
me=$(curl -fsS -c "$jar" -b "$jar" "$GATEWAY/api/core/me")
grep -q "\"email\":\"$email\"" <<<"$me" && grep -q '"displayName":"Neha Rao"' <<<"$me" || fail "new user's profile: $me"
echo "clicking the link verified the email and signed the new user in: $me"
echo "PASS: sign-in, sign-up with email verification, token relay, CSRF and logout work end to end"
