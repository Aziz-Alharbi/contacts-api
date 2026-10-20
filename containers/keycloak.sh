#!/bin/bash
set -eu
secrets=/run/development-secrets
export KC_BOOTSTRAP_ADMIN_PASSWORD="${KC_BOOTSTRAP_ADMIN_PASSWORD:-$(cat "$secrets/keycloak-admin")}"
export CONTACTS_USER_PASSWORD="${CONTACTS_USER_PASSWORD:-$(cat "$secrets/demo-user")}"
export CONTACTS_ADMIN_PASSWORD="${CONTACTS_ADMIN_PASSWORD:-$(cat "$secrets/demo-admin")}"
export CONTACTS_REPORTER_SECRET="${CONTACTS_REPORTER_SECRET:-$(cat "$secrets/reporter")}"
exec /opt/keycloak/bin/kc.sh "$@"
