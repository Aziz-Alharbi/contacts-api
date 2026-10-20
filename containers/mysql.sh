#!/bin/sh
set -eu
export MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-$(cat /run/development-secrets/mysql-root)}"
export MYSQL_PASSWORD="${MYSQL_PASSWORD:-$(cat /run/development-secrets/mysql-app)}"
exec /usr/local/bin/docker-entrypoint.sh "$@"
