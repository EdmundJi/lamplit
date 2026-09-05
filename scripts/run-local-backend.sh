#!/bin/zsh
# Runs the backend against the local docker MySQL/Redis/MinIO on port 8081, so it can coexist
# with the older `growth-backend-1` container that still holds 8080.
#
#   scripts/run-local-backend.sh            # foreground
#   scripts/run-local-backend.sh > log 2>&1 &   # background
#
# Reads .env.local (never print it — it holds the AI key) and clears every ADMIN_BOOTSTRAP_*
# variable: the bootstrap runner refuses to start unless email and password are set together.
set -e
cd "$(dirname "$0")/.."
set -a
. ./.env.local
set +a
for v in ${(k)parameters}; do case $v in ADMIN_BOOTSTRAP_*) unset $v;; esac; done
exec ./backend/mvnw -f backend/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments="--spring.profiles.active=local --server.port=8081"
