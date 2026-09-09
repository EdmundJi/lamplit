#!/bin/zsh
# Runs the headless accelerated town simulation (AcceleratedTownRunnerIT) and exports a readable
# timeline. Never touches the real database or any shared world - see InMemoryWorldStore and the
# class docs on AcceleratedTownRunner for why.
#
# Usage:
#   scripts/run-accelerated-town.sh                          # 3 rule-only days, no model, no tokens spent
#   scripts/run-accelerated-town.sh --days 5                 # 5 rule-only days
#   scripts/run-accelerated-town.sh --model --days 0.02      # real Qwen3.8-Flash calls, ~29 simulated minutes
#   scripts/run-accelerated-town.sh --model --provider deepseek --days 0.02  # explicit fallback probe
#   scripts/run-accelerated-town.sh --model --days 0.1 --out /tmp/my-run
#   scripts/run-accelerated-town.sh --model --days 2 --resume-from /tmp/my-run/world-snapshot.json \
#     --out /tmp/my-run-continued                            # pick up where a previous run left off
#
# --model defaults to the QWEN_* group. --provider deepseek explicitly selects DEEPSEEK_* instead.
# One run uses one provider and never silently falls back. Without --model, no credentials are read.
#
# Output (default backend/target/accelerated-run, or backend/target/accelerated-run-model with
# --model unless --out is given):
#   manifest.json              run inputs (world id, start instant, days, tick size, seed, resumedFrom) - for reruns
#   timeline.json               every diary/event/memory/dialogue/relationship-change entry, chronological
#   timeline.md                  same, human-readable
#   highlights.md                 condensed human-readable read: dialogue/events/reflections only
#   usage.json                    token spend by day and call type (empty/zero without --model)
#   metrics.json / metrics.md     emergence metrics: service requests, personality drift, complaints,
#                                  interruptions, reflections, learned expectations, relationship
#                                  asymmetry, memory divergence, spatial clustering - see MetricsExporter
#   world-snapshot.json           full world state at the end of this run - pass to --resume-from to continue it
#   blind-test/quiz/quiz.txt      up to 20 lines (model-voiced dialogue only, deduped, balanced across
#                                  the four residents) with the speaker stripped
#   blind-test/quiz/quiz.json     same, machine-readable
#   blind-test/key/answer-key.json   the stripped-out answers - a SEPARATE directory from quiz/ on
#                                     purpose, so whoever takes the blind test is only ever handed quiz/
#   blind-test/key/pool-stats.json   how many unique model-voiced lines were actually available per
#                                     resident, so a thin quiz pool is visible instead of silently accepted

set -e
cd "$(dirname "$0")/.."

days=3
model=false
out=""
world_id=""
resume_from=""
model_provider="qwen"

while [ $# -gt 0 ]; do
  case "$1" in
    --days) days="$2"; shift 2 ;;
    --model) model=true; shift ;;
    --provider) model_provider="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --world-id) world_id="$2"; shift 2 ;;
    --resume-from) resume_from="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [ -z "$out" ]; then
  if [ "$model" = "true" ]; then out="target/accelerated-run-model"; else out="target/accelerated-run"; fi
fi

export COMPANION_RUN=true
export COMPANION_RUN_DAYS="$days"
export COMPANION_RUN_MODEL="$model"
export COMPANION_RUN_MODEL_PROVIDER="$model_provider"
export COMPANION_RUN_OUT="$out"
if [ -n "$world_id" ]; then export COMPANION_RUN_WORLD_ID="$world_id"; fi
if [ -n "$resume_from" ]; then export COMPANION_RUN_RESUME_FROM="$resume_from"; fi

if [ "$model" = "true" ]; then
  set -a
  . ./.env.local
  set +a
fi

cd backend
exec ./mvnw test -Dtest=AcceleratedTownRunnerIT
