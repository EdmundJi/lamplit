<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { BatteryMedium, Check, Clock3, Gauge, Minimize2, Play, RotateCcw, SkipForward, Sparkles, TimerReset, Undo2, X } from 'lucide-vue-next'
import { api, type ApiError } from '../../shared/api/client'
import { notifyDataChanged, onDataChanged } from '../../shared/data-sync'
import { randomUUID } from '../../shared/uuid'
import { encouragement, type EncouragementMoment } from '../../shared/encouragement'

type Task = { publicId: string; taskTitle: string; plannedStartAt: string; status: string; roleCode?: string; roleName?: string; estimatedMinutes?: number; difficulty?: number }
type Goal = { publicId: string; title: string; description: string; startDate: string; endDate: string; status: string }
type CheckMood = 'steady' | 'low' | 'open'
type DailyStatus = { publicId: string; localDate: string; energy: 'LOW' | 'STEADY' | 'OPEN'; availableMinutes: number; advice: 'SHRINK' | 'KEEP' | 'LIGHT'; updatedAt: string }

const DAILY_COMPLETION_LIMIT = 4

const tasks = ref<Task[]>([])
const goals = ref<Goal[]>([])
const loading = ref(true)
const error = ref('')
const feedback = ref<{ tone: 'support' | 'celebrate'; text: string; experience?: string } | null>(null)
const last = ref<{ schedule: string; event: string } | null>(null)
const pending = ref(new Set<string>())
const actionKeys = new Map<string, string>()
const selected = ref<{ task: Task; eventType: 'PARTIAL' | 'DEFERRED' } | null>(null)
const completionPercent = ref(50)
const deferredStart = ref(localDateTime(Date.now() + 86400000))

const checkMood = ref<CheckMood>('steady')
const availableMinutes = ref(30)
const checkSubmitted = ref(false)
const savedAdvice = ref<'SHRINK' | 'KEEP' | 'LIGHT' | null>(null)
const recoveryChoice = ref('')
const focusTask = ref<Task | null>(null)
const focusRunning = ref(false)
const focusSeconds = ref(25 * 60)
let focusTimer: ReturnType<typeof setInterval> | undefined

const plannedTasks = computed(() => tasks.value.filter(task => ['PLANNED', 'IN_PROGRESS'].includes(task.status)))
const strainedTasks = computed(() => tasks.value.filter(task => ['DEFERRED', 'SKIPPED', 'EXPIRED'].includes(task.status)))
const activeGoals = computed(() => goals.value.filter(goal => goal.status === 'ACTIVE'))
const completedTaskCount = computed(() => tasks.value.filter(task => task.status === 'DONE').length)
const remainingCompletions = computed(() => Math.max(0, DAILY_COMPLETION_LIMIT - completedTaskCount.value))
const dailyLimitReached = computed(() => remainingCompletions.value === 0)
const suggestedPlan = computed(() => {
  if (checkMood.value === 'low' || availableMinutes.value < 20) {
    return { title: '缩小任务', body: '今天先保留一件最小行动，把完成比例目标降到 50%。', action: '缩小今天', advice: 'SHRINK' }
  }
  if (checkMood.value === 'open' && availableMinutes.value >= 45) {
    return { title: '保持原计划', body: '状态和时间都足够，适合按原计划推进，但仍然保留延期入口。', action: '保持节奏', advice: 'KEEP' }
  }
  return { title: '轻量推进', body: '先完成最靠前的一项任务，剩余任务根据实际精力决定。', action: '先做一项', advice: 'LIGHT' }
})
const activeAdvice = computed(() => savedAdvice.value ?? suggestedPlan.value.advice)
const recommendedTasks = computed(() => {
  const list = tasks.value
    .filter(task => ['PLANNED', 'IN_PROGRESS'].includes(task.status))
    .slice()
  if (activeAdvice.value === 'SHRINK') {
    list.sort((a, b) => (a.estimatedMinutes ?? 99) - (b.estimatedMinutes ?? 99))
  } else {
    list.sort((a, b) => new Date(a.plannedStartAt).getTime() - new Date(b.plannedStartAt).getTime())
  }
  return list
})
const recommendedPublicIds = computed(() => {
  if (activeAdvice.value === 'SHRINK') {
    return new Set(recommendedTasks.value.slice(0, 2).map(task => task.publicId))
  }
  if (activeAdvice.value === 'LIGHT') {
    return new Set(recommendedTasks.value.slice(0, 1).map(task => task.publicId))
  }
  return new Set<string>()
})
const dailyGuidance = computed(() => {
  if (activeAdvice.value === 'SHRINK') return '今日建议：每项控制在 15 分钟以内，完成比例目标降到 50%。'
  if (activeAdvice.value === 'KEEP') return '今日建议：按原计划推进，保留延期入口。'
  return '今日建议：先完成一项，再根据实际精力决定下一项。'
})
const focusMinutes = computed(() => Math.max(5, Math.round(focusSeconds.value / 60)))
const focusClock = computed(() => {
  const minutes = Math.floor(focusSeconds.value / 60).toString().padStart(2, '0')
  const seconds = Math.floor(focusSeconds.value % 60).toString().padStart(2, '0')
  return `${minutes}:${seconds}`
})

function localDateTime(value: number) {
  const date = new Date(value - new Date(value).getTimezoneOffset() * 60000)
  return date.toISOString().slice(0, 16)
}

function localDate(value = new Date()) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

async function load(showLoading = true) {
  if (showLoading) loading.value = true
  error.value = ''
  try {
    const d = localDate()
    const [todayTasks, activeGoalList, status] = await Promise.all([
      api.get<Task[]>(`/task-schedules?localDate=${d}`),
      api.get<Goal[]>('/goals?status=ACTIVE'),
      api.get<DailyStatus | null>('/daily-status').catch(() => null),
    ])
    tasks.value = todayTasks
    goals.value = activeGoalList
    if (status) {
      checkMood.value = status.energy === 'LOW' ? 'low' : status.energy === 'OPEN' ? 'open' : 'steady'
      availableMinutes.value = status.availableMinutes
      savedAdvice.value = status.advice
      checkSubmitted.value = true
    }
  } catch {
    error.value = '今天的任务暂时无法加载'
  } finally {
    if (showLoading) loading.value = false
  }
}

function prepare(task: Task, eventType: 'PARTIAL' | 'DEFERRED') {
  selected.value = { task, eventType }
}

function canActOn(task: Task) {
  return ['PLANNED', 'IN_PROGRESS'].includes(task.status)
}

function canComplete(task: Task) {
  return canActOn(task) && !dailyLimitReached.value
}

async function applyCheck() {
  checkSubmitted.value = true
  try {
    const saved = await api.post<DailyStatus>('/daily-status', {
      energy: checkMood.value === 'low' ? 'LOW' : checkMood.value === 'open' ? 'OPEN' : 'STEADY',
      availableMinutes: availableMinutes.value,
    })
    savedAdvice.value = saved.advice
    notifyDataChanged(['today', 'insights'])
    feedback.value = { tone: 'support', text: `已按“${suggestedPlan.value.title}”整理今天：${suggestedPlan.value.body}` }
    await nextTick()
    focusFirstRecommended()
  } catch {
    error.value = '状态保存失败，请稍后重试'
    checkSubmitted.value = false
  }
}

function focusFirstRecommended() {
  const first = recommendedTasks.value[0]
  if (!first) return
  const row = document.querySelector(`[data-task-id="${first.publicId}"]`)
  row?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function applyRecovery(choice: string) {
  recoveryChoice.value = choice
  feedback.value = { tone: 'support', text: `恢复计划已选：${choice}。系统不会追责中断，今天只需要重新拿回一点节奏。` }
}

function startFocus(task: Task) {
  focusTask.value = task
  focusSeconds.value = Math.max(5, task.estimatedMinutes ?? 25) * 60
  focusRunning.value = false
}

function toggleFocus() {
  focusRunning.value = !focusRunning.value
  if (focusRunning.value) {
    focusTimer = setInterval(() => {
      if (focusSeconds.value <= 1) {
        focusSeconds.value = 0
        focusRunning.value = false
        clearInterval(focusTimer)
        return
      }
      focusSeconds.value -= 1
    }, 1000)
  } else {
    clearInterval(focusTimer)
  }
}

function closeFocus() {
  clearInterval(focusTimer)
  focusRunning.value = false
  focusTask.value = null
}

async function act(task: Task, eventType: string, extra: Record<string, unknown> = {}) {
  if (eventType === 'COMPLETED' && dailyLimitReached.value) {
    error.value = '今天已完成 4 个任务，明天再继续吧'
    return
  }
  const intent = `${task.publicId}:${eventType}`
  if (pending.value.has(intent)) return
  const key = actionKeys.get(intent) ?? randomUUID()
  actionKeys.set(intent, key)
  pending.value.add(intent)
  error.value = ''
  try {
    const result: any = await api.post(`/task-schedules/${task.publicId}/events`, { eventType, ...extra }, { 'Idempotency-Key': key })
    task.status = result.scheduleStatus
    last.value = { schedule: task.publicId, event: result.eventPublicId }
    const moments: Partial<Record<string, EncouragementMoment>> = { STARTED: 'taskStarted', COMPLETED: 'taskCompleted', PARTIAL: 'taskPartial', DEFERRED: 'taskDeferred', SKIPPED: 'taskSkipped' }
    const moment = moments[eventType]
    if (moment) {
      feedback.value = {
        tone: eventType === 'COMPLETED' ? 'celebrate' : 'support',
        text: encouragement(moment),
        experience: [
          result.roleExperienceDelta > 0 ? `${result.roleProgress.roleName} +${result.roleExperienceDelta} 经验 · LV.${result.roleProgress.level}` : '',
          result.coinDelta > 0 ? `金币 +${result.coinDelta}` : '',
        ].filter(Boolean).join(' · ') || undefined,
      }
    }
    notifyDataChanged(['tasks', 'today', 'insights', 'attributes', 'achievements', 'profile', 'partners'])
    actionKeys.delete(intent)
    selected.value = null
  } catch (caught) {
    const apiError = caught as Partial<ApiError>
    error.value = apiError.code === 'DAILY_TASK_COMPLETION_LIMIT_REACHED'
      ? '今天已完成 4 个任务，明天再继续吧'
      : '记录未保存，请重试'
  } finally {
    pending.value.delete(intent)
  }
}

async function confirmAction() {
  if (!selected.value) return
  const { task, eventType } = selected.value
  if (eventType === 'PARTIAL') await act(task, eventType, { completionRatio: completionPercent.value / 100 })
  else await act(task, eventType, { deferredStartAt: new Date(deferredStart.value).toISOString() })
}

async function finishFocus(eventType: 'COMPLETED' | 'PARTIAL') {
  if (!focusTask.value) return
  const task = focusTask.value
  closeFocus()
  if (eventType === 'PARTIAL') await act(task, eventType, { completionRatio: 0.5 })
  else await act(task, eventType)
}

async function reverse() {
  if (!last.value) return
  await api.post(`/task-schedules/${last.value.schedule}/events/${last.value.event}/reverse`, undefined, { 'Idempotency-Key': randomUUID() })
  last.value = null
  feedback.value = null
  await load(false)
  notifyDataChanged(['tasks', 'today', 'insights', 'attributes', 'achievements', 'profile', 'partners'])
}

const stopDataSync = onDataChanged(['goals', 'tasks'], () => load(false))

onMounted(() => load(true))
onBeforeUnmount(() => {
  stopDataSync()
  clearInterval(focusTimer)
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <p class="eyebrow">{{ new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date()) }}</p>
        <h1>今日</h1>
      </div>
      <button v-if="last" class="secondary" @click="reverse">
        <Undo2 :size="17" />
        撤销上次记录
      </button>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="feedback" class="feedback-banner" :data-tone="feedback.tone" role="status" aria-live="polite">
      <Sparkles :size="19" />
      <p>{{ feedback.text }}<strong v-if="feedback.experience">{{ feedback.experience }}</strong></p>
    </div>

    <section class="daily-check band" aria-labelledby="daily-check-title">
      <div class="check-copy">
        <p class="eyebrow">每日状态检查</p>
        <h2 id="daily-check-title">今天用哪种节奏开始？</h2>
      </div>
      <div class="check-controls">
        <div class="mood-control" aria-label="今日精力">
          <button type="button" :aria-pressed="checkMood === 'low'" @click="checkMood = 'low'">偏低</button>
          <button type="button" :aria-pressed="checkMood === 'steady'" @click="checkMood = 'steady'">稳定</button>
          <button type="button" :aria-pressed="checkMood === 'open'" @click="checkMood = 'open'">充足</button>
        </div>
        <label class="minutes-control" for="available-minutes">
          <span>{{ availableMinutes }} 分钟</span>
          <input id="available-minutes" v-model.number="availableMinutes" type="range" min="10" max="90" step="5">
        </label>
      </div>
      <div class="check-result">
        <BatteryMedium :size="18" />
        <div>
          <strong>{{ suggestedPlan.title }}</strong>
          <p>{{ suggestedPlan.body }}</p>
        </div>
        <button class="secondary" type="button" @click="applyCheck">{{ checkSubmitted ? '更新建议' : suggestedPlan.action }}</button>
      </div>
    </section>

    <section v-if="activeGoals.length" class="goal-today band" aria-labelledby="today-goals-title">
      <div class="goal-summary-head">
        <div>
          <p class="eyebrow">进行中的目标</p>
          <h2 id="today-goals-title">今天仍在这个方向里</h2>
        </div>
        <RouterLink class="button secondary" to="/goals">管理目标</RouterLink>
      </div>
      <div class="goal-strip">
        <article v-for="goal in activeGoals" :key="goal.publicId">
          <span class="status">{{ goal.startDate }} 至 {{ goal.endDate }}</span>
          <h3>{{ goal.title }}</h3>
          <p>{{ goal.description || '还没有填写完成标准' }}</p>
        </article>
      </div>
      <p v-if="!tasks.length" class="goal-hint">目标已经保存；当前任务周期没有覆盖今天。</p>
    </section>

    <section v-if="strainedTasks.length || recoveryChoice" class="recovery band" aria-labelledby="recovery-title">
      <div>
        <p class="eyebrow">恢复计划</p>
        <h2 id="recovery-title">中断之后，从更小的版本回来</h2>
        <p class="muted">延期、跳过或过期不会扣回历史努力。这里给你一个重新开始的入口。</p>
      </div>
      <div class="recovery-actions">
        <button type="button" class="secondary" :aria-pressed="recoveryChoice === '今天只保留一件任务'" @click="applyRecovery('今天只保留一件任务')">
          <Minimize2 :size="16" />
          只保留一件
        </button>
        <button type="button" class="secondary" :aria-pressed="recoveryChoice === '把下一步缩到 10 分钟'" @click="applyRecovery('把下一步缩到 10 分钟')">
          <TimerReset :size="16" />
          缩到 10 分钟
        </button>
        <button type="button" class="secondary" :aria-pressed="recoveryChoice === '明天重新开始'" @click="applyRecovery('明天重新开始')">
          <RotateCcw :size="16" />
          明天重启
        </button>
      </div>
    </section>

    <section v-if="!loading && tasks.length" class="task-quota" :data-limit-reached="dailyLimitReached" aria-live="polite">
      <div class="quota-copy">
        <span>今日完成额度</span>
        <strong>{{ completedTaskCount }} / {{ DAILY_COMPLETION_LIMIT }}</strong>
      </div>
      <progress :value="Math.min(completedTaskCount, DAILY_COMPLETION_LIMIT)" :max="DAILY_COMPLETION_LIMIT" :aria-label="`今日已完成 ${completedTaskCount} 个任务，最多 ${DAILY_COMPLETION_LIMIT} 个`" />
      <p>{{ dailyLimitReached ? '今日额度已用完，未完成的任务可以延期或留待明天。' : `还可以完成 ${remainingCompletions} 个任务。` }}</p>
    </section>

    <p v-if="loading" class="empty">正在整理今天的安排…</p>
    <template v-else-if="tasks.length">
      <div v-if="checkSubmitted && activeAdvice !== 'KEEP'" class="daily-guidance" :data-advice="activeAdvice" role="status" aria-live="polite">
        <BatteryMedium :size="17" />
        <span>{{ dailyGuidance }}</span>
      </div>
      <div class="task-list">
        <article v-for="task in recommendedTasks" :key="task.publicId" class="task-row" :class="{ recommended: recommendedPublicIds.has(task.publicId) }" :data-task-id="task.publicId">
          <div>
            <span class="status">{{ task.status }}<template v-if="task.roleName"> · {{ task.roleName }}</template>
              <template v-if="recommendedPublicIds.has(task.publicId)"><span class="recommended-badge">今天先做</span></template>
            </span>
            <h2>{{ task.taskTitle }}</h2>
            <time>{{ new Date(task.plannedStartAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}<template v-if="task.estimatedMinutes"> · 约 {{ task.estimatedMinutes }} 分钟<template v-if="task.difficulty"> · 难度 {{ task.difficulty }}</template></template></time>
          </div>
          <div class="actions">
            <button class="icon-button focus-button" title="专注执行" aria-label="专注执行" :disabled="!canActOn(task)" @click="startFocus(task)">
              <TimerReset :size="18" />
            </button>
            <button v-if="task.status === 'PLANNED'" class="icon-button" title="开始" aria-label="开始" @click="act(task, 'STARTED')">
              <Play />
            </button>
            <button class="icon-button" :title="dailyLimitReached ? '今日完成额度已用完' : '完成'" aria-label="完成" :disabled="!canComplete(task)" @click="act(task, 'COMPLETED')">
              <Check />
            </button>
            <button class="icon-button" title="部分完成" aria-label="部分完成" :disabled="!canActOn(task)" @click="prepare(task, 'PARTIAL')">
              <Gauge />
            </button>
            <button class="icon-button" title="延期" aria-label="延期" :disabled="!canActOn(task)" @click="prepare(task, 'DEFERRED')">
              <Clock3 />
            </button>
            <button v-if="task.status === 'PLANNED'" class="icon-button" title="跳过" aria-label="跳过" @click="act(task, 'SKIPPED')">
              <SkipForward />
            </button>
          </div>
        </article>
        <article v-for="task in tasks.filter(item => !['PLANNED', 'IN_PROGRESS'].includes(item.status))" :key="task.publicId" class="task-row">
          <div>
            <span class="status">{{ task.status }}<template v-if="task.roleName"> · {{ task.roleName }}</template></span>
            <h2>{{ task.taskTitle }}</h2>
            <time>{{ new Date(task.plannedStartAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</time>
          </div>
        </article>
      </div>
    </template>
    <div v-else-if="!loading" class="empty">
      <h2>今天还没有任务</h2>
      <p>{{ activeGoals.length ? '目标已在上方显示，可以为它添加一项覆盖今天的周期任务。' : '可以从目标页安排一项小行动。' }}</p>
      <RouterLink class="button primary" to="/goals">前往目标</RouterLink>
    </div>

    <section v-if="selected" class="action-panel" role="dialog" aria-modal="true" :aria-label="selected.eventType === 'PARTIAL' ? '记录部分完成' : '选择延期时间'">
      <template v-if="selected.eventType === 'PARTIAL'">
        <label for="completion">完成比例：{{ completionPercent }}%</label>
        <input id="completion" v-model.number="completionPercent" type="range" min="10" max="90" step="10">
      </template>
      <template v-else>
        <label for="deferred">新的开始时间</label>
        <input id="deferred" v-model="deferredStart" type="datetime-local" required>
      </template>
      <div class="actions">
        <button class="primary" @click="confirmAction">确认记录</button>
        <button class="secondary" @click="selected = null">取消</button>
      </div>
    </section>

    <section v-if="focusTask" class="focus-panel" role="dialog" aria-modal="true" aria-labelledby="focus-title">
      <button class="icon-button close-focus" type="button" aria-label="关闭专注模式" @click="closeFocus">
        <X :size="18" />
      </button>
      <p class="eyebrow">专注执行模式</p>
      <h2 id="focus-title">{{ focusTask.taskTitle }}</h2>
      <div class="focus-clock" aria-live="polite">{{ focusClock }}</div>
      <p class="muted">先让注意力停在这一件事上。时间到了也可以记录部分完成。</p>
      <div class="actions focus-actions">
        <button type="button" class="secondary" @click="toggleFocus">{{ focusRunning ? '暂停' : '开始' }}</button>
        <button type="button" class="primary" :disabled="dailyLimitReached" @click="finishFocus('COMPLETED')">完成</button>
        <button type="button" class="secondary" @click="finishFocus('PARTIAL')">部分完成</button>
      </div>
      <small>当前专注片段：{{ focusMinutes }} 分钟</small>
    </section>
  </section>
</template>

<style scoped>
.feedback-banner strong { display: block; margin-top: 3px; color: var(--amber); font: 700 13px Inter, "PingFang SC", sans-serif; }
.daily-check { display: grid; grid-template-columns: minmax(260px, .52fr) minmax(0, 1fr); gap: 18px 26px; align-items: stretch; padding: 20px; border: 1px solid var(--border); border-radius: calc(var(--radius) + 4px); background: color-mix(in srgb, var(--surface) 88%, transparent); box-shadow: var(--shadow-soft); overflow: hidden; }
.check-copy h2, .recovery h2 { margin: 0; font-size: 18px; }
.check-controls { min-width: 0; display: grid; grid-template-columns: minmax(220px, 320px) minmax(0, 1fr); gap: 16px; align-items: center; }
.mood-control { min-width: 0; display: grid; grid-template-columns: repeat(3, 1fr); overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius); }
.mood-control button { border: 0; border-right: 1px solid var(--border); border-radius: 0; background: var(--surface); color: var(--muted); }
.mood-control button:last-child { border-right: 0; }
.mood-control button[aria-pressed='true'] { background: linear-gradient(135deg, var(--primary), color-mix(in srgb, var(--primary) 72%, var(--accent))); color: white; font-weight: 800; }
.minutes-control { min-width: 0; width: 100%; display: grid; grid-template-columns: max-content minmax(140px, 1fr); gap: 14px; align-items: center; color: var(--muted); font-size: 13px; }
.minutes-control input { width: 100%; min-width: 0; max-width: 100%; }
.check-result { grid-column: 1 / -1; display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; gap: 12px; align-items: center; padding: 14px; border: 1px solid color-mix(in srgb, var(--primary) 18%, var(--border)); border-radius: var(--radius); background: color-mix(in srgb, var(--primary-soft) 48%, var(--surface)); }
.check-result svg { color: var(--primary); }
.check-result p { margin: 4px 0 0; color: var(--muted); line-height: 1.55; }
.goal-today { display: grid; gap: 14px; }
.goal-summary-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.goal-summary-head h2 { margin: 0; font-size: 18px; }
.goal-strip { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.goal-strip article { min-height: 136px; display: grid; align-content: start; gap: 8px; padding: 14px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 88%, transparent); box-shadow: var(--shadow-soft); }
.goal-strip h3 { margin: 0; font-size: 16px; line-height: 1.35; }
.goal-strip p { margin: 0; color: var(--muted); line-height: 1.55; }
.goal-hint { margin: 0; padding: 11px 12px; border: 1px solid color-mix(in srgb, var(--amber) 28%, var(--border)); border-radius: var(--radius); background: color-mix(in srgb, var(--amber) 8%, var(--surface)); color: var(--muted); line-height: 1.6; }
.recovery { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding-inline: 4px; }
.recovery .muted { margin-bottom: 0; }
.recovery-actions { display: flex; flex-wrap: wrap; gap: 9px; justify-content: flex-end; }
.recovery-actions button[aria-pressed='true'] { border-color: var(--primary); color: var(--primary); font-weight: 700; }
.task-quota { display: grid; grid-template-columns: max-content minmax(140px, 220px) minmax(0, 1fr); gap: 14px; align-items: center; padding: 12px 16px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 90%, transparent); }
.task-quota[data-limit-reached='true'] { border-color: color-mix(in srgb, var(--amber) 34%, var(--border)); background: color-mix(in srgb, var(--amber) 7%, var(--surface)); }
.quota-copy { display: flex; align-items: baseline; gap: 9px; white-space: nowrap; }
.quota-copy span { color: var(--muted); font-size: 13px; }
.quota-copy strong { font-size: 16px; }
.task-quota progress { width: 100%; height: 7px; accent-color: var(--primary); }
.task-quota[data-limit-reached='true'] progress { accent-color: var(--amber); }
.task-quota p { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.5; }
.task-list { display: grid; gap: 10px; }
.task-row { min-height: 92px; display: flex; align-items: center; justify-content: space-between; gap: 16px; border: 1px solid var(--border); border-radius: var(--radius); padding: 14px 16px; background: color-mix(in srgb, var(--surface) 88%, transparent); box-shadow: 0 1px 0 rgb(255 255 255 / 60%) inset; }
.task-row:hover { background: var(--surface); border-color: color-mix(in srgb, var(--primary) 24%, var(--border)); box-shadow: var(--shadow-soft); }
.task-row h2 { font-size: 16px; margin: 4px 0; }
.task-row time { font-size: 13px; color: var(--muted); }
.task-row.recommended { border-color: color-mix(in srgb, var(--primary) 44%, var(--border)); background: color-mix(in srgb, var(--primary-soft) 52%, var(--surface)); }
.recommended-badge { margin-left: 8px; padding: 2px 8px; border-radius: 999px; background: var(--primary); color: white; font-size: 11px; font-weight: 800; }
.daily-guidance { display: flex; align-items: center; gap: 9px; margin-bottom: 12px; padding: 12px 15px; border: 1px solid color-mix(in srgb, var(--primary) 32%, var(--border)); border-radius: var(--radius); background: color-mix(in srgb, var(--primary-soft) 55%, var(--surface)); color: var(--primary-strong); font-size: 13px; font-weight: 700; }
.daily-guidance[data-advice='SHRINK'] { border-color: color-mix(in srgb, var(--amber) 40%, var(--border)); background: color-mix(in srgb, var(--amber) 10%, var(--surface)); color: var(--amber); }
.task-row .actions { flex-wrap: nowrap; }
.task-row .icon-button { border: 1px solid var(--border); }
.focus-button { color: var(--primary); }
.button { display: inline-flex; align-items: center; text-decoration: none; }
.empty .button { margin-top: 10px; }
.action-panel, .focus-panel { position: fixed; z-index: 20; left: 50%; top: 50%; transform: translate(-50%, -50%); width: min(420px, calc(100vw - 32px)); display: grid; gap: 16px; padding: 24px; border: 1px solid var(--border); border-radius: calc(var(--radius) + 6px); background: var(--surface); box-shadow: var(--shadow); }
.action-panel input { width: 100%; }
.focus-panel { text-align: center; }
.close-focus { position: absolute; top: 12px; right: 12px; border: 1px solid var(--border); }
.focus-panel h2 { margin: 0; font-size: 20px; }
.focus-clock { font-size: 48px; font-weight: 900; color: var(--primary); letter-spacing: 0; }
.focus-actions { justify-content: center; }
.focus-panel small { color: var(--muted); }
@media (prefers-reduced-motion: no-preference) {
  .daily-check, .goal-today, .recovery { animation: task-enter var(--motion-medium) ease-out both; }
  .task-row { animation: task-enter var(--motion-medium) ease-out both; transition: background-color var(--motion-fast) ease, transform var(--motion-fast) ease; }
  .task-row:hover { transform: translateX(3px); }
  .task-row:nth-child(2) { animation-delay: 45ms; }
  .task-row:nth-child(3) { animation-delay: 90ms; }
  .task-row:nth-child(4) { animation-delay: 135ms; }
  .action-panel, .focus-panel { animation: panel-pop var(--motion-medium) cubic-bezier(.2,.8,.2,1) both; }
  .focus-clock { animation: clock-breathe 2.5s ease-in-out infinite; }
}
@keyframes task-enter { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
@keyframes panel-pop { from { opacity: 0; transform: translate(-50%, -48%) scale(.98); } to { opacity: 1; transform: translate(-50%, -50%) scale(1); } }
@keyframes clock-breathe { 0%, 100% { opacity: .86; } 50% { opacity: 1; } }
@media (max-width: 760px) {
  .daily-check, .check-controls, .recovery { grid-template-columns: 1fr; align-items: stretch; }
  .goal-summary-head { align-items: flex-start; flex-direction: column; }
  .goal-strip { grid-template-columns: 1fr; }
  .recovery { flex-direction: column; align-items: flex-start; }
  .recovery-actions { width: 100%; justify-content: flex-start; }
  .check-result { grid-template-columns: 24px minmax(0, 1fr); }
  .check-result button { grid-column: 1 / -1; }
}
@media (max-width: 600px) {
  .task-quota { grid-template-columns: 1fr; gap: 8px; }
  .task-row { align-items: flex-start; flex-direction: column; }
  .task-row .actions { width: 100%; display: grid; grid-template-columns: repeat(auto-fit, var(--control)); gap: 8px; padding-top: 4px; justify-content: end; }
  .minutes-control { grid-template-columns: 1fr; }
}
</style>
