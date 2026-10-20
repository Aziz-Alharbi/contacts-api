#!/bin/sh
set -eu
export RABBITMQ_DEFAULT_PASS="${RABBITMQ_DEFAULT_PASS:-$(cat /run/development-secrets/rabbitmq)}"
exec /usr/local/bin/docker-entrypoint.sh "$@"
