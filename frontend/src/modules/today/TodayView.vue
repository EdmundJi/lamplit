<script setup lang="ts">
import { nextTick } from 'vue'
import { BatteryMedium, Check, Clock3, Gauge, Minimize2, Play, RotateCcw, SkipForward, Sparkles, TimerReset, Undo2, X } from 'lucide-vue-next'
import GrowthScene from '../../shared/ui/GrowthScene.vue'
import { taskStatusLabel } from '../../shared/task-status'
import { useDialogFocus } from '../../shared/ui/use-dialog-focus'
import { DAILY_COMPLETION_LIMIT, useTodayLogic } from './today.logic'

const {
  tasks, goals, loading, error, feedback, last,
  selected, completionPercent, deferredStart,
  checkMood, availableMinutes, checkSubmitted, showCheck, recoveryChoice,
  focusTask, focusRunning,
  strainedTasks, activeGoals, completedTaskCount, remainingCompletions, dailyLimitReached,
  suggestedPlan, activeAdvice, recommendedTasks, recommendedPublicIds, dailyGuidance, focusMinutes, focusClock,
  prepare, canActOn, canComplete, applyCheck, applyRecovery, startFocus, toggleFocus, closeFocus,
  act, confirmAction, finishFocus, reverse,
} = useTodayLogic()

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
    <header class="page-head">
      <div>
        <p class="eyebrow">{{ new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date()) }}</p>
        <h1>今天，先向前一小步。</h1>
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

    <section class="today-hero" aria-label="今日起点">
      <div class="next-step">
        <div class="hero-label"><span class="live-dot" /> 今日起点 <span>先做这一件</span></div>
        <template v-if="loading"><h2>正在整理你的下一步…</h2><p>给今天，留一点真实的空间。</p></template>
        <template v-else-if="recommendedTasks[0]">
          <p class="next-step-kicker">从这一件事开始</p>
          <h2>{{ recommendedTasks[0].taskTitle }}</h2>
          <p>{{ recommendedTasks[0].roleName || '属于你的成长行动' }}<span v-if="recommendedTasks[0].estimatedMinutes"> · 约 {{ recommendedTasks[0].estimatedMinutes }} 分钟</span></p>
          <div class="hero-actions"><button class="primary" :disabled="!canActOn(recommendedTasks[0])" @click="startFocus(recommendedTasks[0])"><Play :size="16" />专注这一步</button><button class="rhythm-toggle" :aria-expanded="showCheck" aria-controls="daily-rhythm" @click="showCheck = !showCheck"><BatteryMedium :size="16" />调整今日节奏</button></div>
        </template>
        <template v-else><p class="next-step-kicker">每一步，都有它的意义</p><h2>{{ tasks.length ? '今天留下的努力，都在这里。' : '从一件做得到的小事开始。' }}</h2><p>{{ tasks.length ? '可以回望一下，也可以让自己休息片刻。' : '不用排满今天，先给一个想法留出位置。' }}</p><RouterLink class="button primary" :to="tasks.length ? '/insights' : '/goals'">{{ tasks.length ? '看看成长记录' : '安排一件小事' }}</RouterLink></template>
      </div>
      <RouterLink class="today-scene" to="/town"><GrowthScene /><span class="scene-caption"><span><strong>我的街角</strong><small>场景预览 · 去看看你的成长小镇</small></span><span class="scene-arrow">↗</span></span></RouterLink>
    </section>
    <button v-if="!recommendedTasks.length" class="rhythm-toggle standalone-rhythm" :aria-expanded="showCheck" aria-controls="daily-rhythm" @click="showCheck = !showCheck"><BatteryMedium :size="16" />调整今日节奏</button>
    <section v-show="showCheck" id="daily-rhythm" class="daily-check band" aria-labelledby="daily-check-title">
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
            <span class="status">{{ taskStatusLabel(task.status) }}<template v-if="task.roleName"> · {{ task.roleName }}</template>
              <template v-if="recommendedPublicIds.has(task.publicId)"><span class="recommended-badge">今天先做</span></template>
            </span>
            <h2>{{ task.taskTitle }}</h2>
            <time>{{ new Date(task.plannedStartAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}<template v-if="task.estimatedMinutes"> · 约 {{ task.estimatedMinutes }} 分钟<template v-if="task.difficulty"> · 难度 {{ task.difficulty }}</template></template></time>
          </div>
          <div class="actions">
            <button v-if="task.status === 'PLANNED'" class="secondary" title="开始" aria-label="开始" :disabled="!canActOn(task)" @click="act(task, 'STARTED')">
              <Play :size="16" />开始
            </button>
            <button class="primary" :title="dailyLimitReached ? '今日完成额度已用完' : '完成'" aria-label="完成" :disabled="!canComplete(task)" @click="act(task, 'COMPLETED')">
              <Check :size="16" />完成
            </button>
            <details class="task-more"><summary aria-label="更多任务操作">更多</summary><div>
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
        <article v-for="task in tasks.filter(item => !['PLANNED', 'IN_PROGRESS'].includes(item.status))" :key="task.publicId" class="task-row">
          <div>
            <span class="status">{{ taskStatusLabel(task.status) }}<template v-if="task.roleName"> · {{ task.roleName }}</template></span>
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
.today-page { display: flex; flex-direction: column; gap: 20px; }
.today-page > .page-head { margin-bottom: 4px; }
.today-hero { display: grid; grid-template-columns: 1.3fr 1fr; border: 1px solid var(--border); background: var(--surface); border-radius: var(--radius-scene); overflow: hidden; }
.next-step { padding: 28px 32px; min-width: 0; display: flex; flex-direction: column; align-items: flex-start; justify-content: center; }
.hero-label { display: flex; align-items: center; gap: 8px; color: var(--muted); font-size: 12px; letter-spacing: .04em; font-weight: 650; }
.hero-label > span:last-child { margin-left: 8px; letter-spacing: 0; }
.live-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); }
.next-step .next-step-kicker { margin: 26px 0 8px; font-size: 12px; color: var(--muted); }
.next-step h2 { font-size: clamp(23px, 2.4vw, 32px); line-height: 1.4; letter-spacing: -.6px; margin: 0; }
.next-step p { color: var(--muted); font-size: 13px; margin: 12px 0 20px; }
.hero-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 16px; }
.rhythm-toggle { display: inline-flex; align-items: center; gap: 6px; background: transparent; color: var(--muted); font-size: 12px; padding: 0; }
.standalone-rhythm { align-self: flex-start; }
.today-scene { position: relative; display: block; overflow: hidden; min-height: 280px; color: var(--on-forest); text-decoration: none; background: var(--forest); }
.today-scene :deep(.growth-scene) { height: 100%; }
.scene-caption { position: absolute; bottom: 0; left: 0; right: 0; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 22px; background: linear-gradient(transparent, #173d32e8); }
.scene-caption strong, .scene-caption small { display: block; }
.scene-caption strong { font-size: 15px; font-weight: 500; }
.scene-caption small { font-size: 10px; opacity: .75; margin-top: 4px; }
.scene-arrow { display: grid; place-items: center; border: 1px solid #a2b49370; border-radius: 50%; width: 34px; height: 34px; }
.task-more { position: relative; }
.task-more summary { display: grid; place-items: center; min-width: 66px; height: 44px; padding: 0 12px; border: 1px solid var(--border); border-radius: var(--radius); cursor: pointer; list-style: none; font-size: 14px; font-weight: 650; color: var(--muted); }
.task-more summary::-webkit-details-marker { display: none; }
.task-more summary::marker { content: ''; }
.task-more > div { position: absolute; right: 0; top: 48px; z-index: 8; width: 168px; padding: 6px; display: grid; gap: 4px; background: var(--surface); border: 1px solid var(--border); box-shadow: var(--shadow); border-radius: var(--radius); }
.task-more > div button { justify-content: flex-start; border: 0; }
.today-dialog { z-index: 51 !important; max-height: calc(100dvh - 40px); overflow-y: auto; }
@media (max-width: 760px) {
  .today-hero { grid-template-columns: 1fr; }
  .today-scene { min-height: 108px; height: 108px; order: -1; }
  .today-scene :deep(svg) { width: 240px; margin-left: auto; }
  .scene-caption { top: 0; padding: 18px; background: linear-gradient(90deg, #173d32, transparent); }
  .scene-arrow { display: none; }
  .next-step { padding: 22px; }
  .next-step .next-step-kicker { margin-top: 16px; }
  .today-page { gap: 16px; }
  .today-page .task-row .actions { display: flex; width: 100%; flex-wrap: wrap; justify-content: flex-end; }
}
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
  .task-row .actions { width: 100%; display: flex; flex-wrap: wrap; gap: 8px; padding-top: 6px; justify-content: flex-start; }
  .task-row .actions > button, .task-row .actions .task-more { flex: 1 1 auto; min-width: 96px; }
  .task-row .actions .task-more summary { width: 100%; }
  .minutes-control { grid-template-columns: 1fr; }
}
</style>
