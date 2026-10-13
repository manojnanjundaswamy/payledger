#!/usr/bin/env bash
# Brings an existing local Keycloak realm up to date with deploy/local/keycloak/payledger-realm.json.
# Keycloak imports that file only when the realm doesn't exist yet, so clients added later (like the admin
# console's payledger-core-admin) need this once on a laptop whose Keycloak volume predates them. Safe to rerun.
#
#   docker compose up -d keycloak && scripts/keycloak-sync.sh
set -euo pipefail

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
