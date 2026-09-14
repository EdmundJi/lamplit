#!/bin/zsh
# Runs the headless accelerated town simulation (AcceleratedTownRunnerIT) and exports a readable
# timeline. Never touches the real database or any shared world - see InMemoryWorldStore and the
# class docs on AcceleratedTownRunner for why.
#
# Usage:
#   scripts/run-accelerated-town.sh                          # 3 rule-only days, no model, no tokens spent
#   scripts/run-accelerated-town.sh --days 5                 # 5 rule-only days
#   scripts/run-accelerated-town.sh --model --days 0.02      # real pinned Qwen calls, ~29 simulated minutes
#   scripts/run-accelerated-town.sh --model --days 0.5 --experiment lunch-gathering   # files it under experiments/
#   scripts/run-accelerated-town.sh --model --provider deepseek --days 0.02 --allow-model-override  # not for experiments
#   scripts/run-accelerated-town.sh --model --days 0.1 --out /tmp/my-run
#   scripts/run-accelerated-town.sh --model --days 0.1 --max-calls 24 --input-token-stop 1500000
#   scripts/run-accelerated-town.sh --model --days 2 --resume-from /tmp/my-run/world-snapshot.json \
#     --out /tmp/my-run-continued                            # pick up where a previous run left off
#
# Experiments (docs/04, experiments/README.md): model runs must use the pinned snapshot below; anything
# else is refused unless --allow-model-override, which also refuses --experiment. --experiment <slug>
# writes to experiments/<today>-<slug>/data/<run-id>/ and records git commit + dirty state next to it.
#
# --model defaults to the QWEN_* group. --provider deepseek explicitly selects DEEPSEEK_* instead.
# One run uses one provider and never silently falls back. Without --model, no credentials are read.
# --max-calls is a strict actual HTTP request ceiling across the whole run, including JSON repair
# requests and day boundaries. Logical model calls and wire requests are reported separately.
# --input-token-stop stops dispatch after reported usage reaches the value; it is not a preflight
# tokenizer limit, so calls already in flight (up to --parallelism / COMPANION_RUN_PARALLELISM) can
# report a bounded tail above it. Pair it with --max-calls whenever spend must have a hard ceiling.
#
# Output (default backend/target/accelerated-run, or backend/target/accelerated-run-model with
# --model unless --out is given):
#   manifest.json              run inputs (world id, start instant, days, tick size, seed, resumedFrom) - for reruns
#   timeline.json               every diary/event/memory/dialogue/relationship-change entry, chronological
#   timeline.md                  same, human-readable
#   highlights.md                 condensed human-readable read: dialogue/events/reflections only
#   usage.json                    token spend by day and call type (empty/zero without --model)
#   model-wire-requests.json       one ordinal and request kind for every actual HTTP dispatch
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
daily_budget=""
max_calls=""
input_token_stop=""
parallelism=""
experiment=""
allow_override=false
PINNED_MODEL="qwen3.7-flash-2026-07-15"

while [ $# -gt 0 ]; do
  case "$1" in
    --days) days="$2"; shift 2 ;;
    --model) model=true; shift ;;
    --provider) model_provider="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --world-id) world_id="$2"; shift 2 ;;
    --resume-from) resume_from="$2"; shift 2 ;;
    --daily-budget) daily_budget="$2"; shift 2 ;;
    --max-calls) max_calls="$2"; shift 2 ;;
    --input-token-stop) input_token_stop="$2"; shift 2 ;;
    --parallelism) parallelism="$2"; shift 2 ;;
    --experiment) experiment="$2"; shift 2 ;;
    --allow-model-override) allow_override=true; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [ -n "$experiment" ]; then
  if [ "$allow_override" = "true" ]; then echo "--experiment cannot be combined with --allow-model-override" >&2; exit 1; fi
  run_id="${world_id:-run}-$(date +%H%M%S)"
  out="$PWD/experiments/$(date +%F)-$experiment/data/$run_id"
  mkdir -p "$out"
fi
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
if [ -n "$daily_budget" ]; then export COMPANION_RUN_MODEL_BUDGET="$daily_budget"; fi
if [ -n "$max_calls" ]; then export COMPANION_RUN_TOTAL_MODEL_BUDGET="$max_calls"; fi
if [ -n "$input_token_stop" ]; then export COMPANION_RUN_INPUT_TOKEN_STOP="$input_token_stop"; fi
if [ -n "$parallelism" ]; then export COMPANION_RUN_PARALLELISM="$parallelism"; fi

if [ "$model" = "true" ]; then
  set -a
  . ./.env.local
  set +a
  if [ "$allow_override" != "true" ]; then
    if [ "$model_provider" != "qwen" ] || [ "${QWEN_MODEL:-}" != "$PINNED_MODEL" ]; then
      echo "Refusing: experiments use qwen $PINNED_MODEL (got provider=$model_provider model=${QWEN_MODEL:-unset}). Pass --allow-model-override for a non-experiment probe." >&2
      exit 1
    fi
  fi
fi

if [ -n "$experiment" ]; then
  cat > "$out/run-info.json" <<JSON
{"experiment":"$experiment","startedAt":"$(date -u +%FT%TZ)","gitCommit":"$(git rev-parse HEAD)","gitDirtyFiles":$(git status --porcelain | wc -l | tr -d ' '),"model":$([ "$model" = "true" ] && echo "\"${QWEN_MODEL}\"" || echo null),"days":"$days","worldId":"${world_id:-}","resumeFrom":"${resume_from:-}","parallelism":"${parallelism:-}","maxCalls":"${max_calls:-}"}
JSON
  git diff > "$out/uncommitted.diff"
fi

cd backend
exec ./mvnw test -Dtest=AcceleratedTownRunnerIT
