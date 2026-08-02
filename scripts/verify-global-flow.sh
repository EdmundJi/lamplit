#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[[ -f .env.local ]] || { echo '.env.local is required' >&2; exit 1; }

set -a
source .env.local
set +a

docker compose --env-file .env.local -f deploy/compose.yaml up -d
for attempt in {1..60}; do
  if docker compose --env-file .env.local -f deploy/compose.yaml ps --format json | jq -se 'all(.Health == "healthy")' >/dev/null; then break; fi
  [[ "$attempt" -lt 60 ]] || { docker compose --env-file .env.local -f deploy/compose.yaml ps; exit 1; }
  sleep 2
done

(cd backend && ./mvnw -o verify)
(cd frontend && CI=true pnpm lint && CI=true pnpm test --run && CI=true pnpm build)
(cd frontend && CI=true pnpm exec playwright test -c playwright.config.ts)

./scripts/smoke-api.sh

DB_CHECKS="$(docker compose --env-file .env.local -f deploy/compose.yaml exec -T mysql sh -lc 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -u"$MYSQL_USER" "$MYSQL_DATABASE" -e "select count(*) >= 3 from consent_record; select count(*) >= 1 from growth_goal; select count(*) >= 4 from task_event; select count(*) >= 1 from ai_safety_event; select count(*) >= 1 from data_export_job; select count(*) >= 4 from user_role_progress; select count(*) = 200 from task_template where review_status = 0x5055424C4953484544; select count(*) >= 1 from task_preset_quota where refresh_count = 3;"')"
[[ "$(printf '%s\n' "$DB_CHECKS" | sed '/^$/d' | wc -l | tr -d ' ')" -eq 8 ]]
! printf '%s\n' "$DB_CHECKS" | grep -q '^0$'
docker compose --env-file .env.local -f deploy/compose.yaml exec -T redis redis-cli --no-auth-warning -a "$REDIS_PASSWORD" ping | grep -q PONG

if [[ -f logs/backend.log ]]; then
  ! rg -n 'Cookie:|refresh_token=|access_token=|password[^a-z].*Correct-Horse|sk-[A-Za-z0-9_-]{20,}' logs/backend.log
fi
echo 'Global verification gate: PASS'
