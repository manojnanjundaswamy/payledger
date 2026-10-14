#!/usr/bin/env bash
# Brings an existing local Keycloak realm up to date with deploy/local/keycloak/payledger-realm.json.
# Keycloak imports that file only when the realm doesn't exist yet, so clients added later (like the admin
# console's payledger-core-admin) need this once on a laptop whose Keycloak volume predates them. Safe to rerun.
#
#   docker compose up -d keycloak && scripts/keycloak-sync.sh
set -euo pipefail
# Git Bash on Windows would turn /opt/keycloak/... into C:/Program Files/Git/opt/...
export MSYS_NO_PATHCONV=1

# SMTP_* and others from .env, the same file docker compose reads.
ENV_FILE=${ENV_FILE:-.env}
if [ -f "$ENV_FILE" ]; then set -a; . "./$ENV_FILE"; set +a; fi

SECRET=${IDENTITY_ADMIN_CLIENT_SECRET:-local-dev-core-admin-secret}
kc() { docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"; }

kc config credentials --server http://localhost:8080 --realm master --user "${KC_ADMIN:-admin}" --password "${KC_ADMIN_PASSWORD:-admin}" >/dev/null

if kc get clients -r payledger -q clientId=payledger-core-admin --fields clientId | grep -q payledger-core-admin; then
  echo "payledger-core-admin: already there"
else
  kc create clients -r payledger \
    -s clientId=payledger-core-admin -s 'name=PayLedger admin console (core-service)' -s enabled=true \
    -s publicClient=false -s clientAuthenticatorType=client-secret -s "secret=$SECRET" \
    -s standardFlowEnabled=false -s directAccessGrantsEnabled=false -s implicitFlowEnabled=false \
    -s serviceAccountsEnabled=true >/dev/null
  echo "payledger-core-admin: created"
fi
# Users only, this realm only.
kc add-roles -r payledger --uusername service-account-payledger-core-admin \
  --cclientid realm-management --rolename manage-users --rolename view-users
echo "payledger-core-admin: may manage and view users"

# Sign-up verification and password emails are sent by Keycloak. With SMTP_HOST set (in .env), they go through that
# provider to real inboxes; without it, to Mailpit (http://localhost:8025), which catches everything locally.
host=${SMTP_HOST:-mailpit}
if [ "$host" = "mailpit" ]; then
  kc update realms/payledger -s smtpServer.host=mailpit -s smtpServer.port=1025 -s smtpServer.auth=false     -s smtpServer.starttls=false -s smtpServer.ssl=false -s "smtpServer.from=${MAIL_FROM:-no-reply@payledger.local}"     -s smtpServer.fromDisplayName=PayLedger
  echo "Keycloak email: Mailpit (local only; see http://localhost:8025)"
else
  kc update realms/payledger -s "smtpServer.host=$host" -s "smtpServer.port=${SMTP_PORT:-587}"     -s "smtpServer.auth=${SMTP_AUTH:-true}" -s "smtpServer.user=${SMTP_USERNAME:-}" -s "smtpServer.password=${SMTP_PASSWORD:-}"     -s "smtpServer.starttls=${SMTP_STARTTLS:-true}" -s "smtpServer.ssl=${SMTP_SSL:-false}"     -s "smtpServer.from=${MAIL_FROM:?set MAIL_FROM to the address emails come from}" -s smtpServer.fromDisplayName=PayLedger
  echo "Keycloak email: $host (real delivery)"
fi

# Hosted (PUBLIC_URL in .env, e.g. https://payledger.example.com): sign-in may return to that address too. The local
# addresses stay, so the laptop keeps working.
gw=$(kc get clients -r payledger -q clientId=payledger-gateway --fields id --format csv --noquotes)
web="http://localhost:${WEB_PORT:-5173}"; gateway="http://localhost:${GATEWAY_PORT:-8000}"
uris="\"$gateway/login/oauth2/code/keycloak\",\"$web/login/oauth2/code/keycloak\""
origins="\"$web\",\"$gateway\""
logout="$web*##$gateway*"
if [ -n "${PUBLIC_URL:-}" ]; then
  uris="$uris,\"${PUBLIC_URL%/}/login/oauth2/code/keycloak\""
  origins="$origins,\"${PUBLIC_URL%/}\""
  logout="$logout##${PUBLIC_URL%/}*"
fi
kc update "clients/$gw" -r payledger -s "redirectUris=[$uris]" -s "webOrigins=[$origins]" \
  -s "attributes.\"post.logout.redirect.uris\"=$logout"
echo "Sign-in returns to: localhost${PUBLIC_URL:+ and $PUBLIC_URL}"

# "Continue with Google" on the sign-in and sign-up pages, once GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are in .env
# (docs/hosting.md, "Google sign-in"). People who sign in with Google get a PayLedger account with their Google email,
# already verified; an existing account with that email is linked after they confirm it.
if [ -n "${GOOGLE_CLIENT_ID:-}" ] && [ -n "${GOOGLE_CLIENT_SECRET:-}" ]; then
  if kc get identity-provider/instances/google -r payledger >/dev/null 2>&1; then
    kc update identity-provider/instances/google -r payledger -s enabled=true \
      -s "config.clientId=$GOOGLE_CLIENT_ID" -s "config.clientSecret=$GOOGLE_CLIENT_SECRET" >/dev/null
  else
    kc create identity-provider/instances -r payledger -s alias=google -s providerId=google -s enabled=true \
      -s trustEmail=true -s 'displayName=Google' -s firstBrokerLoginFlowAlias="first broker login" \
      -s "config.clientId=$GOOGLE_CLIENT_ID" -s "config.clientSecret=$GOOGLE_CLIENT_SECRET" \
      -s config.defaultScope="openid email profile" -s config.syncMode=IMPORT >/dev/null
  fi
  echo "Google sign-in: on"
else
  echo "Google sign-in: off (set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in .env)"
fi
