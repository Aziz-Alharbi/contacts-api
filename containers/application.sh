#!/bin/sh
set -eu
secrets=/run/development-secrets
case "${APPLICATION}" in
  api)
    export DB_PASSWORD="${DB_PASSWORD:-$(cat "$secrets/mysql-app")}";;
  reporter)
    export CONTACTS_REPORTER_SECRET="${CONTACTS_REPORTER_SECRET:-$(cat "$secrets/reporter")}";;
  *) echo 'Unknown application' >&2; exit 1;;
esac
export RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-$(cat "$secrets/rabbitmq")}"
exec java ${JAVA_OPTS:--XX:MaxRAMPercentage=50} -Djava.util.logging.manager=org.jboss.logmanager.LogManager -jar /deployments/quarkus-run.jar "$@"
