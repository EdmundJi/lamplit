#!/usr/bin/env bash
set -euo pipefail

API_BASE="${API_BASE:-http://127.0.0.1:8080/api/v1}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
COOKIE_JAR="$WORK_DIR/cookies.txt"
OTHER_COOKIE_JAR="$WORK_DIR/other-cookies.txt"
NOW="$(date +%s)"
TODAY="$(date +%F)"
END_DATE="$(date -v+27d +%F)"
WEEK_START="$(date -v-$(($(date +%u)-1))d +%F)"
WEEK_END="$(date -v+6d +%F)"

pass() { printf '%-30s PASS\n' "$1"; }
fail() { printf '%-30s FAIL: %s\n' "$1" "$2" >&2; exit 1; }
json() { jq -cer "$2" "$1"; }
csrf() { awk '$6 == "csrf_token" { value=$7 } END { print value }' "$COOKIE_JAR"; }
request() {
  local method="$1" path="$2" body="${3:-}" key="${4:-}"
  local accept='application/json'
  [[ "$path" == *':stream' ]] && accept='text/event-stream'
  [[ "$path" == /privacy/exports/*/content ]] && accept='application/zip'
  local args=(-sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X "$method" "$API_BASE$path" -H "Accept: $accept")
  if [[ "$method" != GET ]]; then args+=(-H "X-CSRF-Token: $(csrf)"); fi
  if [[ -n "$key" ]]; then args+=(-H "Idempotency-Key: $key"); fi
  if [[ -n "$body" ]]; then args+=(-H "Content-Type: application/json" --data "$body"); fi
  curl "${args[@]}"
}

register_payload="$(jq -cn --arg email "flow-$NOW@example.test" '{email:$email,password:"Correct-Horse-Battery-2026!",displayName:"Global Flow",birthDate:"1990-01-01",timezone:"Asia/Shanghai",consents:{terms:"2026-07",privacy:"2026-07",ai:"2026-07"}}')"
curl -sS -c "$COOKIE_JAR" -H 'Content-Type: application/json' --data "$register_payload" "$API_BASE/auth/register" > "$WORK_DIR/register.json"
json "$WORK_DIR/register.json" '.data.publicId' >/dev/null || fail auth_cookies "registration failed"
[[ -n "$(csrf)" ]] || fail auth_cookies "csrf cookie missing"
pass auth_cookies

request GET /me/consents > "$WORK_DIR/consents.json"
[[ "$(jq '.data | length' "$WORK_DIR/consents.json")" -eq 3 ]] || fail consent_rows "expected three consent rows"
pass consent_rows

request GET /dimensions > "$WORK_DIR/dimensions.json"
DIMENSION_ID="$(json "$WORK_DIR/dimensions.json" '.data[0].publicId')"

request GET '/task-presets?role=STUDENT' > "$WORK_DIR/presets-1.json"
request GET '/task-presets?role=STUDENT' > "$WORK_DIR/presets-2.json"
[[ "$(jq '.data.items | length' "$WORK_DIR/presets-1.json")" -eq 4 ]] || fail career_task_presets "expected four presets"
jq -S '.data.items | map(.publicId)' "$WORK_DIR/presets-1.json" > "$WORK_DIR/preset-ids-1.json"
jq -S '.data.items | map(.publicId)' "$WORK_DIR/presets-2.json" > "$WORK_DIR/preset-ids-2.json"
cmp "$WORK_DIR/preset-ids-1.json" "$WORK_DIR/preset-ids-2.json" >/dev/null || fail career_task_presets "daily draw was not stable"
request POST '/task-presets/refresh?role=STUDENT' > "$WORK_DIR/presets-refresh-1.json"
request POST '/task-presets/refresh?role=FITNESS_USER' > "$WORK_DIR/presets-refresh-2.json"
request POST '/task-presets/refresh?role=WORKER' > "$WORK_DIR/presets-refresh-3.json"
[[ "$(jq '.data.refreshesRemaining' "$WORK_DIR/presets-refresh-3.json")" -eq 0 ]] || fail career_task_presets "refresh quota did not reach zero"
LIMIT_STATUS="$(curl -sS -o "$WORK_DIR/presets-limit.json" -w '%{http_code}' -b "$COOKIE_JAR" -X POST "$API_BASE/task-presets/refresh?role=EMOTIONAL_SUPPORT_USER" -H "X-CSRF-Token: $(csrf)" -H 'Accept: application/json')"
[[ "$LIMIT_STATUS" == 429 ]] || fail career_task_presets "fourth refresh returned $LIMIT_STATUS"
pass career_task_presets

request POST /goals "$(jq -cn --arg d "$DIMENSION_ID" --arg s "$TODAY" --arg e "$END_DATE" '{dimensionPublicId:$d,title:"四周深度学习",description:"完成四个可验证章节",startDate:$s,endDate:$e}')" > "$WORK_DIR/goal.json"
GOAL_ID="$(json "$WORK_DIR/goal.json" '.data.publicId')"
request POST /plans/weekly "$(jq -cn --arg g "$GOAL_ID" --arg w "$WEEK_START" '{goalPublicId:$g,weekStartDate:$w,timezone:"Asia/Shanghai"}')" > "$WORK_DIR/plan.json"
PLAN_ID="$(json "$WORK_DIR/plan.json" '.data.publicId')"
PRESET="$(jq -c '.data.items[0]' "$WORK_DIR/presets-refresh-1.json")"
request POST /tasks "$(jq -cn --arg p "$PLAN_ID" --arg from "$TODAY" --arg until "$WEEK_END" --argjson preset "$PRESET" '{weeklyPlanPublicId:$p,title:$preset.name,notes:$preset.notes,estimatedMinutes:$preset.estimatedMinutes,difficulty:$preset.difficulty,rrule:"FREQ=DAILY",dimensionWeights:{($preset.dimensionCode):$preset.dimensionWeight},plannedLocalTime:$preset.plannedLocalTime,activeFrom:$from,activeUntil:$until,sourceTemplatePublicId:$preset.publicId,roleCode:$preset.roleCode}')" > "$WORK_DIR/task-1.json"
json "$WORK_DIR/task-1.json" '.data.sourceTemplatePublicId' >/dev/null || { cat "$WORK_DIR/task-1.json" >&2; fail goal_plan_task_rows "preset task was rejected"; }
for index in 2 3 4; do
  request POST /tasks "$(jq -cn --arg p "$PLAN_ID" --arg title "数据流任务 $index" --arg from "$TODAY" --arg until "$WEEK_END" '{weeklyPlanPublicId:$p,title:$title,notes:"",estimatedMinutes:25,difficulty:2,rrule:"FREQ=DAILY",dimensionWeights:{KNOWLEDGE:10},plannedLocalTime:"19:00:00",activeFrom:$from,activeUntil:$until,roleCode:"STUDENT"}')" > "$WORK_DIR/task-$index.json"
  json "$WORK_DIR/task-$index.json" '.data.publicId' >/dev/null || { cat "$WORK_DIR/task-$index.json" >&2; fail goal_plan_task_rows "task $index was rejected"; }
done
request POST "/plans/weekly/$PLAN_ID/materialize" '{}' > "$WORK_DIR/materialize.json"
[[ "$(jq '.data | length' "$WORK_DIR/materialize.json")" -ge 4 ]] || { cat "$WORK_DIR/materialize.json" >&2; fail goal_plan_task_rows "materialization did not create schedules"; }
pass goal_plan_task_rows

request GET "/task-schedules?localDate=$TODAY" > "$WORK_DIR/schedules.json"
SCHEDULE_1="$(json "$WORK_DIR/schedules.json" '.data[0].publicId')"
SCHEDULE_2="$(json "$WORK_DIR/schedules.json" '.data[1].publicId')"
SCHEDULE_3="$(json "$WORK_DIR/schedules.json" '.data[2].publicId')"
SCHEDULE_4="$(json "$WORK_DIR/schedules.json" '.data[3].publicId')"
request POST "/task-schedules/$SCHEDULE_1/events" '{"eventType":"STARTED"}' event-start > /dev/null
request POST "/task-schedules/$SCHEDULE_1/events" '{"eventType":"COMPLETED"}' event-complete > "$WORK_DIR/complete.json"
[[ "$(jq '.data.roleExperienceDelta' "$WORK_DIR/complete.json")" -gt 0 ]] || fail career_progression "completion did not award career experience"
[[ "$(json "$WORK_DIR/complete.json" '.data.roleProgress.roleCode')" == STUDENT ]] || fail career_progression "completion updated the wrong career"
request GET /progress/roles > "$WORK_DIR/role-progress.json"
[[ "$(jq '.data | length' "$WORK_DIR/role-progress.json")" -eq 4 ]] || fail career_progression "expected four career progress rows"
pass career_progression
request POST "/task-schedules/$SCHEDULE_2/events" '{"eventType":"PARTIAL","completionRatio":0.5}' event-partial > /dev/null
request POST "/task-schedules/$SCHEDULE_3/events" "$(jq -cn --arg at "$(date -u -v+1d +%Y-%m-%dT%H:%M:%SZ)" '{eventType:"DEFERRED",deferredStartAt:$at}')" event-defer > /dev/null
request POST "/task-schedules/$SCHEDULE_4/events" '{"eventType":"SKIPPED"}' event-skip > /dev/null
EVENT_ID="$(json "$WORK_DIR/complete.json" '.data.eventPublicId')"
request POST "/task-schedules/$SCHEDULE_1/events/$EVENT_ID/reverse" '{}' event-reverse > /dev/null
pass event_states

request POST /ai/sessions '{"scene":"STUDY"}' > "$WORK_DIR/ai-session.json"
AI_SESSION="$(json "$WORK_DIR/ai-session.json" '.data.publicId')"
request POST "/ai/sessions/$AI_SESSION/messages:stream" '{"message":"帮我安排今天的学习"}' > "$WORK_DIR/ai.sse"
grep -Eq 'event:[[:space:]]*done' "$WORK_DIR/ai.sse" || { cat "$WORK_DIR/ai.sse" >&2; fail ai_stream "done event missing"; }
request POST /ai/sessions '{"scene":"EMOTIONAL_SUPPORT"}' > "$WORK_DIR/crisis-session.json"
CRISIS_SESSION="$(json "$WORK_DIR/crisis-session.json" '.data.publicId')"
request POST "/ai/sessions/$CRISIS_SESSION/messages:stream" '{"message":"我不想活了"}' > "$WORK_DIR/crisis.sse"
grep -Eq 'event:[[:space:]]*safety' "$WORK_DIR/crisis.sse" || { cat "$WORK_DIR/crisis.sse" >&2; fail safety_response "safety event missing"; }
! grep -Eq 'event:[[:space:]]*delta' "$WORK_DIR/crisis.sse" || fail safety_response "unsafe delta emitted"
pass ai_stream
pass safety_response

request POST /ai/suggestions "$(jq -cn --arg g "$GOAL_ID" '{scene:"STUDY",goalPublicId:$g,prompt:"给我两个可完成的学习任务"}')" > "$WORK_DIR/suggestions.json"
SUGGESTION_ID="$(jq -r '.data.publicId // empty' "$WORK_DIR/suggestions.json")"
[[ -n "$SUGGESTION_ID" ]] || { cat "$WORK_DIR/suggestions.json" >&2; fail ai_suggestions "suggestion generation failed"; }
request POST "/ai/suggestions/$SUGGESTION_ID/adopt" "$(jq -cn --arg p "$PLAN_ID" --arg a "$TODAY" --arg u "$WEEK_END" '{weeklyPlanPublicId:$p,activeFrom:$a,activeUntil:$u}')" adoption-once > "$WORK_DIR/adopt-1.json"
request POST "/ai/suggestions/$SUGGESTION_ID/adopt" "$(jq -cn --arg p "$PLAN_ID" --arg a "$TODAY" --arg u "$WEEK_END" '{weeklyPlanPublicId:$p,activeFrom:$a,activeUntil:$u}')" adoption-once > "$WORK_DIR/adopt-2.json"
jq -S '.data' "$WORK_DIR/adopt-1.json" > "$WORK_DIR/adopt-1-data.json"
jq -S '.data' "$WORK_DIR/adopt-2.json" > "$WORK_DIR/adopt-2-data.json"
cmp "$WORK_DIR/adopt-1-data.json" "$WORK_DIR/adopt-2-data.json" >/dev/null || fail idempotency_counts "adoption replay differs"
pass idempotency_counts

request POST /privacy/exports '' export-once > "$WORK_DIR/export.json"
EXPORT_ID="$(jq -r '.data.publicId // empty' "$WORK_DIR/export.json")"
[[ -n "$EXPORT_ID" ]] || { cat "$WORK_DIR/export.json" >&2; fail export_object "export request failed"; }
request GET "/privacy/exports/$EXPORT_ID/content" > "$WORK_DIR/export.zip"
unzip -t "$WORK_DIR/export.zip" >/dev/null || fail export_object "invalid zip"
[[ "$(unzip -Z1 "$WORK_DIR/export.zip" | wc -l | tr -d ' ')" -eq 5 ]] || fail export_object "unexpected archive manifest"
unzip -Z1 "$WORK_DIR/export.zip" | grep -q '^role_progress.csv$' || fail export_object "career progress export missing"
pass export_object

other_payload="$(jq -cn --arg email "other-$NOW@example.test" '{email:$email,password:"Correct-Horse-Battery-2026!",displayName:"Other User",birthDate:"1990-01-01",timezone:"Asia/Shanghai",consents:{terms:"2026-07",privacy:"2026-07",ai:"2026-07"}}')"
curl -sS -c "$OTHER_COOKIE_JAR" -H 'Content-Type: application/json' --data "$other_payload" "$API_BASE/auth/register" >/dev/null
STATUS="$(curl -sS -o /dev/null -w '%{http_code}' -b "$OTHER_COOKIE_JAR" "$API_BASE/goals/$GOAL_ID")"
[[ "$STATUS" == 404 ]] || fail ownership_isolation "cross-user goal returned $STATUS"
pass ownership_isolation

request POST /privacy/deletion '{}' > "$WORK_DIR/deletion.json"
[[ "$(json "$WORK_DIR/deletion.json" '.data.status')" == COOLING_OFF ]] || fail deletion_lifecycle "cooling-off missing"
request POST /privacy/deletion/cancel '{}' > "$WORK_DIR/cancel.json"
[[ "$(json "$WORK_DIR/cancel.json" '.data.status')" == CANCELLED ]] || fail deletion_lifecycle "cancellation missing"
pass deletion_lifecycle

printf '\nGlobal API flow passed. Artifacts validated in temporary storage.\n'
