<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../../shared/api/client'
import { roles } from '../goals/goals.logic'
import { notifyDataChanged } from '../../shared/data-sync'
import { BatteryMedium, Check, Clock3, Gauge, Minimize2, Play, RotateCcw, SkipForward, TimerReset, Undo2, X } from 'lucide-vue-next'
import TownPaperScene from '../../shared/ui/TownPaperScene.vue'
import { taskStatusLabel } from '../../shared/task-status'
import { useDialogFocus } from '../../shared/ui/use-dialog-focus'
import { DAILY_COMPLETION_LIMIT, localDate, useTodayLogic, type Task } from './today.logic'

const pageDate = ref(localDate())
const dateParts = computed(() => pageDate.value.split('-'))
const dateLabel = computed(() => new Intl.DateTimeFormat('zh-CN', { weekday: 'long' }).format(new Date(`${pageDate.value}T12:00:00`)))
const isToday = computed(() => pageDate.value === localDate())

const {
  tasks, loading, error, feedback, last, load, pending,
  selected, completionPercent, deferredStart,
  checkMood, availableMinutes, checkSubmitted, showCheck, recoveryChoice,
  focusTask, focusRunning,
  strainedTasks, activeGoals, completedTaskCount, remainingCompletions, dailyLimitReached,
  suggestedPlan, activeAdvice, recommendedTasks, recommendedPublicIds, dailyGuidance, focusMinutes, focusClock,
  prepare, canActOn, canComplete, applyCheck, applyRecovery, startFocus, toggleFocus, closeFocus,
  act, confirmAction, finishFocus, reverse,
} = useTodayLogic({ date: pageDate })


const draft = ref('')
const draftGoal = ref('')
const draftMinutes = ref(15)
const draftRole = ref('STUDENT')
const selectedRole = computed(() => roles.find(role => role.code === draftRole.value) ?? roles[0])
const saving = ref(false)
const input = ref<HTMLInputElement | null>(null)
const editing = ref<Task | null>(null)
const editTitle = ref('')
const editInput = ref<HTMLTextAreaElement[]>([])
const eligibleGoals = computed(() => activeGoals.value.filter(goal => goal.startDate <= pageDate.value && goal.endDate >= pageDate.value))
watch(eligibleGoals, goals => { if (!goals.some(goal => goal.publicId === draftGoal.value)) draftGoal.value = goals[0]?.publicId ?? '' })
let feedbackTimer: ReturnType<typeof setTimeout> | undefined
watch(feedback, value => {
  clearTimeout(feedbackTimer)
  if (value) feedbackTimer = setTimeout(() => { feedback.value = null }, 4500)
})
onBeforeUnmount(() => clearTimeout(feedbackTimer))
watch(pageDate, () => { editing.value = null })
function changeDate(event: Event) {
  const value = (event.target as HTMLInputElement).value
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) pageDate.value = value
}
async function addTask() {
  if (saving.value || !draft.value.trim() || !draftGoal.value) return
  saving.value = true
  error.value = ''
  try {
    await api.post('/tasks', {
      goalPublicId: draftGoal.value, title: draft.value.trim(), notes: '',
      estimatedMinutes: draftMinutes.value, difficulty: 2, rrule: null,
      plannedLocalTime: '09:00', activeFrom: pageDate.value, activeUntil: pageDate.value,
      roleCode: selectedRole.value.code, dimensionWeights: { [selectedRole.value.dimension]: 10 },
    })
    draft.value = ''
    await load(false)
    feedback.value = { tone: 'support', text: '已写入这一天。完成后，勾一下就好。' }
    notifyDataChanged(['tasks', 'goals', 'today'])
  } catch { error.value = '事项未保存，文字已保留，请重试。' }
  finally { saving.value = false; await nextTick(); input.value?.focus() }
}
async function beginEdit(task: Task) {
  document.querySelector<HTMLDetailsElement>(`[data-task-id="${task.publicId}"] details`)?.removeAttribute('open')
  editing.value = task
  editTitle.value = task.taskTitle
  await nextTick()
  editInput.value[0]?.focus()
}
async function cancelEdit() {
  const id = editing.value?.publicId
  editing.value = null
  await nextTick()
  document.querySelector<HTMLElement>(`[data-task-id="${id}"] summary`)?.focus()
}
function editKeydown(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  void saveEdit()
}
async function saveEdit() {
  const task = editing.value
  if (!task?.taskPublicId || !editTitle.value.trim() || saving.value) return
  saving.value = true
  error.value = ''
  try {
    await api.patch(`/tasks/${task.taskPublicId}`, { title: editTitle.value.trim() })
    task.taskTitle = editTitle.value.trim()
    editing.value = null
    feedback.value = { tone: 'support', text: '修改已保存，记录仍在原来的位置。' }
    notifyDataChanged(['tasks', 'goals', 'today'])
    await nextTick()
    document.querySelector<HTMLElement>(`[data-task-id="${task.publicId}"] summary`)?.focus()
  } catch { error.value = '修改未保存，编辑内容已保留，请重试。' }
  finally { saving.value = false }
}
async function completeTask(task: Task) {
  await act(task, 'COMPLETED')
  if (task.status === 'DONE' && feedback.value) feedback.value.text = '已完成，记录已经留在这一页。'
  await nextTick()
  const next = document.querySelector<HTMLButtonElement>('.task-check:not(:disabled)')
  ;(next ?? input.value)?.focus()
}

useDialogFocus(() => Boolean(selected.value || focusTask.value), '.today-dialog', () => { selected.value = null; closeFocus() })

function focusFirstRecommended() {
  const first = recommendedTasks.value[0]
  if (!first) return
  const row = document.querySelector(`[data-task-id="${first.publicId}"]`)
  row?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

async function submitCheck() {
  if (!(await applyCheck())) return
  await nextTick()
  focusFirstRecommended()
}
</script>

<template>
  <section class="page today-page">
    <header class="page-head journal-heading">
      <div><p class="eyebrow">THE DAILY JOURNAL / 日常，值得记下</p><h1>把日子，写成自己。</h1></div>
      <span class="edition">一日一页<br>{{ dateParts[0] }} · {{ dateParts[1] }}</span>
    </header>
    <div class="journal-spread">
      <div class="date-block"><span class="date-month">{{ dateParts[0] }} / {{ dateParts[1] }}</span><strong class="date-number">{{ dateParts[2] }}</strong><span class="date-weekday">{{ dateLabel }}<i />{{ isToday ? '今天' : pageDate < localDate() ? '翻看与补记' : '提前安排' }}</span><label class="date-picker">翻到某一天<input aria-label="手账日期" type="date" :value="pageDate" :disabled="saving || pending.size > 0" @change="changeDate"></label><button v-if="!isToday" class="rhythm-toggle" @click="pageDate = localDate()">回到今天 ↗</button></div>
      <div class="opening-note"><p class="eyebrow">{{ isToday ? '今日页' : '日常页' }} / {{ String(tasks.length).padStart(2, '0') }} 件小事</p><h2>{{ loading ? '翻开这一页…' : tasks.length && !recommendedTasks.length ? '做过的事，\n都有回响。' : '留一点空白，\n做一点喜欢的事。' }}</h2><p>不必把每一格填满。<br>写下，去做，然后留下一个完成的记号。</p><button v-if="isToday" class="rhythm-toggle" :aria-expanded="showCheck" aria-controls="daily-rhythm" @click="showCheck = !showCheck"><BatteryMedium :size="15" />调整今日节奏</button></div>
      <RouterLink class="today-scene" to="/town"><TownPaperScene /><span class="scene-caption"><span><small>FIG. 01 / 生活在纸上生长</small><strong>去我的小镇走走 <span aria-hidden="true">↗</span></strong></span></span></RouterLink>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="record-receipt" aria-live="polite"><div v-if="feedback" class="feedback-banner" :data-tone="feedback.tone" role="status"><Check :size="16"/><p>{{ feedback.text }}<strong v-if="feedback.experience">{{ feedback.experience }}</strong></p></div><button v-if="last" class="rhythm-toggle" @click="reverse"><Undo2 :size="15"/>撤销上次记录</button></div>
    <div class="list-heading"><h2>这一日的清单<span> / DAILY NOTES</span></h2><span>{{ completedTaskCount }} 件已留下完成痕迹</span></div>
    <form class="quick-entry" @submit.prevent="addTask">
      <span class="entry-plus" aria-hidden="true">＋</span><input ref="input" v-model="draft" aria-label="新事项" placeholder="写下一件小事…" maxlength="200" :disabled="saving" @keydown.enter="($event.isComposing || $event.keyCode === 229) && $event.preventDefault()"><button type="submit" class="primary" :disabled="saving || !draft.trim() || !draftGoal">{{ saving ? '保存中…' : '记下 ↵' }}</button>
      <div class="entry-options"><label>归于 <select v-model="draftGoal" aria-label="事项所属目标" :disabled="saving || !eligibleGoals.length"><option v-if="!eligibleGoals.length" value="">这一天还没有目标</option><option v-for="goal in eligibleGoals" :key="goal.publicId" :value="goal.publicId">{{ goal.title }}</option></select></label><label><select v-model="draftRole" aria-label="事项类别" :disabled="saving"><option v-for="role in roles" :key="role.code" :value="role.code">{{ role.name }}</option></select></label><label>预计 <select v-model="draftMinutes" aria-label="预计分钟" :disabled="saving"><option :value="5">5 分钟</option><option :value="15">15 分钟</option><option :value="25">25 分钟</option><option :value="45">45 分钟</option></select></label><span>Enter 记下 · 单次事项</span><RouterLink v-if="!eligibleGoals.length" to="/goals">先安排一个覆盖这天的目标 ↗</RouterLink></div>
    </form>
    <section v-show="isToday && showCheck" id="daily-rhythm" class="daily-check band" aria-labelledby="daily-check-title">
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
        <button class="secondary" type="button" @click="submitCheck">{{ checkSubmitted ? '更新建议' : suggestedPlan.action }}</button>
      </div>
    </section>

    <section v-if="!loading && tasks.length" class="task-quota" :data-limit-reached="dailyLimitReached" aria-live="polite">
      <div class="quota-copy">
        <span>完成记录</span>
        <strong>{{ completedTaskCount }} / {{ DAILY_COMPLETION_LIMIT }}</strong>
      </div>
      <progress :value="Math.min(completedTaskCount, DAILY_COMPLETION_LIMIT)" :max="DAILY_COMPLETION_LIMIT" :aria-label="`今日已完成 ${completedTaskCount} 个任务，最多 ${DAILY_COMPLETION_LIMIT} 个`" />
      <p>{{ dailyLimitReached ? '这一天的完成额度已用完，可以延期安排。' : `还可以完成 ${remainingCompletions} 个任务。` }}</p>
    </section>

    <p v-if="loading" class="empty">正在整理今天的安排…</p>
    <template v-else-if="tasks.length">
      <div v-if="isToday && checkSubmitted && activeAdvice !== 'KEEP'" v-show="showCheck" class="daily-guidance" :data-advice="activeAdvice" role="status" aria-live="polite">
        <BatteryMedium :size="17" />
        <span>{{ dailyGuidance }}</span>
      </div>
      <div class="task-list">
        <article v-for="task in recommendedTasks" :key="task.publicId" class="task-row" :class="{ recommended: recommendedPublicIds.has(task.publicId) }" :data-task-id="task.publicId">
          <button class="task-check" :title="dailyLimitReached ? '今日完成额度已用完' : '完成'" aria-label="完成" :disabled="!canComplete(task)" @click="completeTask(task)"><span aria-hidden="true" /></button>
          <div class="task-copy">
            <span class="status">{{ taskStatusLabel(task.status) }}<template v-if="task.roleName"> · {{ task.roleName }}</template>
              <template v-if="recommendedPublicIds.has(task.publicId)"><span class="recommended-badge">今天先做</span></template>
            </span>
            <form v-if="editing?.publicId === task.publicId" class="inline-edit" @submit.prevent="saveEdit"><textarea ref="editInput" v-model="editTitle" rows="3" aria-label="修改事项标题" maxlength="200" :disabled="saving" @keydown.esc="cancelEdit" @keydown.enter="editKeydown" /><div class="actions"><button class="primary" :disabled="saving || !editTitle.trim()">保存</button><button type="button" class="secondary" :disabled="saving" @click="cancelEdit">取消</button></div><small>修改任务标题；周期任务会同步更新。Esc 取消</small></form>
            <h2 v-else>{{ task.taskTitle }}</h2>
            <time>{{ new Date(task.plannedStartAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}<template v-if="task.estimatedMinutes"> · 约 {{ task.estimatedMinutes }} 分钟<template v-if="task.difficulty"> · 难度 {{ task.difficulty }}</template></template></time>
          </div>
          <div class="actions">
            <button v-if="task.status === 'PLANNED'" class="secondary" title="开始" aria-label="开始" :disabled="!canActOn(task)" @click="act(task, 'STARTED')">
              <Play :size="16" />开始
            </button>
            <details class="task-more"><summary aria-label="更多任务操作">•••</summary><div><button v-if="task.taskPublicId" class="secondary edit-task" type="button" :disabled="saving" @click="beginEdit(task)">编辑标题</button>
            <button class="secondary" title="专注执行" aria-label="专注执行" :disabled="!canActOn(task)" @click="startFocus(task)">
              <TimerReset :size="16" />专注执行
            </button>
            <button class="secondary" title="部分完成" aria-label="部分完成" :disabled="!canActOn(task)" @click="prepare(task, 'PARTIAL')">
              <Gauge :size="16" />部分完成
            </button>
            <button class="secondary" title="延期" aria-label="延期" :disabled="!canActOn(task)" @click="prepare(task, 'DEFERRED')">
              <Clock3 :size="16" />延期
            </button>
            <button v-if="task.status === 'PLANNED'" class="secondary" title="跳过" aria-label="跳过" :disabled="!canActOn(task)" @click="act(task, 'SKIPPED')">
              <SkipForward :size="16" />跳过
            </button>
            </div></details>
          </div>
        </article>
        <article v-for="task in tasks.filter(item => !['PLANNED', 'IN_PROGRESS'].includes(item.status))" :key="task.publicId" class="task-row recorded-row" :class="{ 'is-done': task.status === 'DONE' }"><span class="record-mark" aria-hidden="true">{{ task.status === 'DONE' ? '✓' : '—' }}</span>
          <div>
            <span class="status">{{ taskStatusLabel(task.status) }}<template v-if="task.roleName"> · {{ task.roleName }}</template></span>
            <h2>{{ task.taskTitle }}</h2>
            <time>{{ new Date(task.plannedStartAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</time>
          </div>
        </article>
      </div>
    </template>
    <div v-else-if="!loading" class="empty">
      <h2>这一页，留给接下来的你。</h2>
      <p>{{ activeGoals.length ? '在上面写下一件小事，按 Enter，开始这一页。' : '先定下一个方向，再写下今天想做的小事。' }}</p>
      <RouterLink class="button primary" to="/goals">前往目标</RouterLink>
    </div>

    <section v-if="activeGoals.length" class="goal-today band" aria-labelledby="today-goals-title">
      <div class="goal-summary-head">
        <div>
          <p class="eyebrow">进行中的目标</p>
          <h2 id="today-goals-title">页边的方向</h2>
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

    <Teleport to="body">
    <div v-if="selected || focusTask" class="dialog-backdrop" @click="selected = null; closeFocus()" />
    <section v-if="selected" class="action-panel today-dialog" role="dialog" aria-modal="true" tabindex="-1" :aria-label="selected.eventType === 'PARTIAL' ? '记录部分完成' : '选择延期时间'">
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

    <section v-if="focusTask" class="focus-panel today-dialog" role="dialog" aria-modal="true" tabindex="-1" aria-labelledby="focus-title">
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
    </Teleport>
  </section>
</template>

<style scoped>
.today-page { width: min(1200px, 100%); padding: 24px 40px 80px; display: flex; flex-direction: column; gap: 0; }
.journal-heading { border-bottom: 1px solid var(--border); padding-bottom: 18px; margin-bottom: 0; }
.journal-heading h1 { font-family: 'Songti SC', 'Noto Serif CJK SC', 'STSong', serif; font-size: clamp(30px, 3.3vw, 46px); font-weight: 900; letter-spacing: -.04em; }
.eyebrow { color: var(--muted); font-size: 10px; letter-spacing: .16em; }
.edition { font: 12px/1.8 'SFMono-Regular', Consolas, monospace; text-align: right; color: var(--muted); }
.journal-spread { display: grid; grid-template-columns: 180px 1fr 300px; align-items: center; gap: 36px; min-height: 260px; padding: 12px 0; }
.date-block { display: flex; align-items: flex-start; flex-direction: column; gap: 8px; }
.date-month { font: 12px 'SFMono-Regular', Consolas, monospace; letter-spacing: .12em; }
.date-number { font: 112px/.95 Georgia, 'Times New Roman', serif; letter-spacing: -.08em; margin-left: -5px; color: var(--primary); }
.date-weekday { display: flex; align-items: center; gap: 9px; font-size: 12px; margin-top: 6px; }
.date-weekday i { width: 3px; height: 3px; background: var(--primary); border-radius: 50%; }
.date-picker { display: grid; gap: 4px; color: var(--muted); font-size: 10px; margin-top: 12px; }
.date-picker input { width: 150px; min-height: 32px; padding: 0; border: 0; border-bottom: 1px solid var(--border); background: transparent; color: var(--ink); font: 12px 'SFMono-Regular', Consolas, monospace; }
.opening-note h2 { white-space: pre-line; font-family: 'Songti SC', 'STSong', serif; font-size: clamp(23px, 2.5vw, 32px); line-height: 1.65; font-weight: 600; margin: 10px 0 14px; }
.opening-note > p:not(.eyebrow) { font-size: 12px; line-height: 1.9; color: var(--muted); }
.rhythm-toggle { display: inline-flex; align-items: center; gap: 6px; background: transparent; border: 0; padding: 0; color: var(--muted); font-size: 12px; min-height: 36px; font-weight: 500; }
.rhythm-toggle:hover { color: var(--primary); }
.today-scene { min-width: 0; color: var(--ink); text-decoration: none; display: block; }
.today-scene :deep(svg) { height: 190px; transition: transform 180ms ease; }
.today-scene:hover :deep(svg) { transform: translateY(-4px); }
.scene-caption { display: block; border-top: 1px solid var(--border); padding: 10px 0; margin: -6px 25px 0; }
.scene-caption small { color: var(--muted); font-size: 9px; letter-spacing: .1em; }
.scene-caption strong { display: flex; justify-content: space-between; font-size: 13px; margin-top: 4px; font-weight: 500; }
.record-receipt { display: flex; justify-content: space-between; gap: 16px; align-items: center; min-height: 28px; }
.feedback-banner { padding: 4px 0; margin: 0; background: transparent; border: 0; color: var(--ink); gap: 6px; grid-template-columns: 18px 1fr; }
.feedback-banner p { font-size: 12px; }
.feedback-banner strong { color: var(--muted); display: inline; font-size: 11px; font-weight: 400; margin-left: 10px; }
.feedback-banner svg { color: var(--primary); }
.list-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; border-top: 2px solid var(--ink); padding: 16px 0; }
.list-heading h2 { font-size: 17px; margin: 0; }
.list-heading h2 span { margin-left: 10px; color: var(--muted); font: 10px 'SFMono-Regular', Consolas, monospace; letter-spacing: .06em; }
.list-heading > span { font-size: 11px; color: var(--muted); }
.quick-entry { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: center; gap: 6px 12px; padding: 12px 0 16px; border-bottom: 1px solid var(--ink); }
.entry-plus { font-size: 25px; color: var(--primary); }
.quick-entry > input { border: 0; min-width: 0; padding: 10px 0; background: transparent; color: var(--ink); font-size: 18px; }
.quick-entry input::placeholder { color: #77756d; }
.entry-options { grid-column: 2 / -1; display: flex; flex-wrap: wrap; align-items: center; gap: 8px 20px; color: var(--muted); font-size: 11px; }
.entry-options label { display: flex; align-items: center; gap: 5px; max-width: 100%; }
.entry-options select { min-width: 0; max-width: 240px; background: transparent; border: 0; border-bottom: 1px solid var(--border); color: var(--ink); padding: 5px 0; border-radius: 0; }
.entry-options a { color: var(--primary); }
.task-quota { order: 0; display: flex; align-items: center; flex-wrap: wrap; gap: 14px; color: var(--muted); font-size: 11px; padding: 12px 0; }
.quota-copy { display: flex; align-items: center; gap: 8px; }
.quota-copy strong { font-family: 'SFMono-Regular', Consolas, monospace; font-weight: 500; }
.task-quota progress { width: 50px; height: 3px; accent-color: var(--primary); }
.task-quota p { margin: 0; }
.task-list { display: grid; gap: 0; }
.task-row { display: flex; align-items: center; gap: 16px; border-bottom: 1px solid var(--border); padding: 22px 0; min-width: 0; }
.task-copy, .recorded-row > div { flex: 1; min-width: 0; }
.task-row h2 { font-size: 19px; font-weight: 500; line-height: 1.65; margin: 4px 0; overflow-wrap: anywhere; }
.task-row .status, .task-row time { font-size: 11px; color: var(--muted); font-weight: 400; }
.task-row time { font-family: 'SFMono-Regular', Consolas, monospace; }
.recommended-badge { color: var(--primary); margin-left: 12px; font-size: 10px; }
.task-check { display: grid; place-items: center; width: 32px; flex: 0 0 32px; padding: 0; background: transparent; }
.task-check span { display: block; width: 20px; height: 20px; border: 1px solid var(--ink); }
.task-check:hover:not(:disabled) span { border-color: var(--primary); background: var(--primary-soft); }
.task-check:active:not(:disabled) span { background: var(--primary); }
.task-row > .actions { flex-wrap: nowrap; gap: 8px; }
.task-row > .actions > .secondary { padding: 0 10px; border: 0; background: transparent; font-size: 12px; color: var(--muted); }
.task-more { position: relative; }
.task-more summary { display: grid; place-items: center; width: 40px; min-height: 44px; cursor: pointer; color: var(--muted); list-style: none; letter-spacing: 2px; }
.task-more summary::-webkit-details-marker { display: none; }
.task-more > div { position: absolute; top: 44px; right: 0; z-index: 8; width: 170px; padding: 6px; display: grid; gap: 4px; background: var(--surface); border: 1px solid var(--border); box-shadow: 0 8px 24px #22221f12; }
.task-more button { border: 0; justify-content: flex-start; font-size: 12px; }
.record-mark { width: 32px; flex: 0 0 32px; color: var(--primary); font: 26px Georgia, serif; text-align: center; }
.is-done h2 { color: var(--muted); text-decoration: line-through; text-decoration-color: var(--primary); text-decoration-thickness: 1px; }
.inline-edit { display: grid; gap: 8px; margin: 10px 0; }
.inline-edit textarea { width: 100%; min-width: 0; border: 0; border-bottom: 1px solid var(--primary); background: var(--surface); color: var(--ink); font-size: 18px; padding: 8px 0; resize: vertical; line-height: 1.7; }
.inline-edit small { color: var(--muted); }
.empty { border: 0; border-bottom: 1px solid var(--border); border-radius: 0; text-align: left; background: transparent; padding: 38px 44px 48px; }
.empty h2 { font-family: 'Songti SC', serif; font-size: 24px; font-weight: 500; }
.empty p { font-size: 13px; }
.empty .button { font-size: 12px; margin-top: 8px; }
.goal-today { margin-top: 36px; padding: 20px 0; border-top: 1px solid var(--ink); }
.goal-summary-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.goal-summary-head h2 { font-family: 'Songti SC', serif; margin: 0; font-size: 22px; }
.goal-summary-head .button { font-size: 12px; background: transparent; border: 0; }
.goal-strip { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin-top: 20px; gap: 28px; }
.goal-strip article { border-left: 1px solid var(--border); padding-left: 16px; min-width: 0; }
.goal-strip h3 { font-size: 14px; font-weight: 600; margin: 6px 0; overflow-wrap: anywhere; }
.goal-strip p, .goal-hint, .goal-strip .status { color: var(--muted); font-size: 11px; }
.daily-check { background: var(--surface-muted); border: 0; padding: 20px; margin: 16px 0; display: grid; gap: 16px; }
.check-copy h2, .recovery h2 { margin: 0; font-size: 18px; }
.check-controls { display: flex; align-items: center; flex-wrap: wrap; gap: 24px; }
.mood-control { display: flex; gap: 8px; }
.mood-control button { padding: 0 18px; background: var(--surface); color: var(--ink); border: 1px solid var(--border); }
.mood-control button[aria-pressed='true'] { background: var(--ink); color: var(--surface); }
.minutes-control { display: flex; align-items: center; gap: 12px; font-size: 12px; }
.check-result { display: flex; gap: 12px; align-items: center; }
.check-result > div { flex: 1; }
.check-result p { margin: 4px 0; font-size: 12px; color: var(--muted); }
.daily-guidance { font-size: 12px; color: var(--muted); display: flex; gap: 8px; margin: 10px 0; }
.recovery { display: flex; align-items: center; flex-wrap: wrap; gap: 16px; margin-top: 20px; }
.recovery p { font-size: 12px; }
.recovery-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.recovery-actions button { font-size: 12px; }
.recovery-actions button[aria-pressed='true'] { border-color: var(--primary); color: var(--primary); }
.today-dialog { --surface: #faf9f6; --ink: #22221f; --muted: #686761; --border: #d8d5cd; --primary: #bd422e; --radius: 3px; color-scheme: light; color: var(--ink); position: fixed; z-index: 51; left: 50%; top: 50%; transform: translate(-50%, -50%); width: min(440px, calc(100vw - 32px)); max-height: calc(100dvh - 40px); overflow-y: auto; display: grid; gap: 16px; padding: 30px; border: 1px solid var(--border); background: var(--surface); box-shadow: 0 24px 80px #2223; }
.today-dialog input { width: 100%; color: var(--ink); background: var(--surface); min-height: 44px; }
.today-dialog :focus-visible { outline: 2px solid var(--primary); outline-offset: 3px; }
.focus-panel { text-align: center; }
.close-focus { position: absolute; right: 12px; top: 12px; }
.focus-panel h2 { font-size: 20px; overflow-wrap: anywhere; }
.focus-clock { font: 64px Georgia, serif; color: var(--primary); }
.focus-actions { justify-content: center; }
.focus-panel small { color: var(--muted); }
@media (max-width: 1100px) {
  .journal-spread { grid-template-columns: 140px 1fr 280px; gap: 22px; }
  .date-number { font-size: 96px; }
}
@media (max-width: 760px) {
  .today-page { padding: 28px 22px 60px; }
  .journal-spread { grid-template-columns: 120px 1fr; gap: 20px; padding: 24px 0 8px; }
  .today-scene { grid-column: 1 / -1; display: grid; grid-template-columns: 190px 1fr; align-items: center; }
  .scene-caption { margin: 0; }
  .opening-note h2 { font-size: 24px; }
  .edition { display: none; }
  .list-heading { flex-wrap: wrap; gap: 6px; }
  .list-heading h2 span { font-size: 9px; }
  .entry-options { grid-column: 1 / -1; }
  .entry-options select { max-width: 210px; }
  .quick-entry > input { font-size: 16px; }
  .quick-entry { column-gap: 8px; }
  .task-row { gap: 8px; flex-wrap: wrap; padding: 18px 0; }
  .task-row h2 { font-size: 17px; }
  .task-row > .actions { margin-left: 40px; justify-content: flex-end; width: calc(100% - 40px); }
  .task-row > .actions > .secondary { min-height: 32px; }
  .goal-strip { grid-template-columns: 1fr; gap: 20px; }
  .record-receipt { flex-wrap: wrap; gap: 0; }
  .check-result { flex-wrap: wrap; }
  .check-result > div { min-width: 180px; }
}
@media (prefers-reduced-motion: reduce) { .today-scene :deep(svg) { height: 190px; transition: none; transform: none !important; } }
</style>
