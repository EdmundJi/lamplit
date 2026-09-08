#!/bin/zsh
# Runs the headless accelerated town simulation (AcceleratedTownRunnerIT) and exports a readable
# timeline. Never touches the real database or any shared world - see InMemoryWorldStore and the
# class docs on AcceleratedTownRunner for why.
#
# Usage:
#   scripts/run-accelerated-town.sh                          # 3 rule-only days, no model, no tokens spent
#   scripts/run-accelerated-town.sh --days 5                 # 5 rule-only days
#   scripts/run-accelerated-town.sh --model --days 0.02      # real DeepSeek calls, ~29 simulated minutes
#   scripts/run-accelerated-town.sh --model --days 0.1 --out /tmp/my-run
#
# --model reads QWEN_BASE_URL/QWEN_API_KEY/QWEN_MODEL/QWEN_TIMEOUT from .env.local (never printed
# or written anywhere else). Without --model, no credentials are read and no network call is made.
#
# Output (default backend/target/accelerated-run, or backend/target/accelerated-run-model with
# --model unless --out is given):
#   manifest.json        run inputs (world id, start instant, days, tick size, seed) - for reruns
#   timeline.json         every diary/event/memory/dialogue/relationship-change entry, chronological
#   timeline.md            same, human-readable
#   highlights.md           condensed human-readable read: dialogue/events/reflections only
#   usage.json              token spend by day and call type (empty/zero without --model)
#   blind-test/quiz.txt      ~20 lines with the speaker stripped, for the "guess who said it" test
#   blind-test/answer-key.json   the stripped-out answers, kept separate from quiz.txt on purpose

set -e
cd "$(dirname "$0")/.."

days=3
model=false
out=""
world_id=""

while [ $# -gt 0 ]; do
  case "$1" in
    --days) days="$2"; shift 2 ;;
    --model) model=true; shift ;;
    --out) out="$2"; shift 2 ;;
    --world-id) world_id="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [ -z "$out" ]; then
  if [ "$model" = "true" ]; then out="target/accelerated-run-model"; else out="target/accelerated-run"; fi
fi

export COMPANION_RUN=true
export COMPANION_RUN_DAYS="$days"
export COMPANION_RUN_MODEL="$model"
export COMPANION_RUN_OUT="$out"
if [ -n "$world_id" ]; then export COMPANION_RUN_WORLD_ID="$world_id"; fi

if [ "$model" = "true" ]; then
  set -a
  . ./.env.local
  set +a
fi

cd backend
exec ./mvnw test -Dtest=AcceleratedTownRunnerIT
