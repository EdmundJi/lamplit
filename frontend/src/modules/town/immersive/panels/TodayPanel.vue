<script setup lang="ts">
import { inject } from 'vue'
import { BatteryMedium, Check, Clock3, Gauge, Play, RefreshCw, SkipForward, Sparkles, TimerReset, Undo2, X } from 'lucide-vue-next'
import { taskStatusLabel } from '../../../../shared/task-status'
import { useDialogFocus } from '../../../../shared/ui/use-dialog-focus'
import { worldBridgeKey } from '../panel.types'
import { openFullPage } from './shared'
import { DAILY_COMPLETION_LIMIT, useTodayLogic } from '../../../today/today.logic'

const FULL_PAGE = '/today'

const bridge = inject(worldBridgeKey, undefined)

const {
  tasks, loading, error, feedback, last,
  selected, completionPercent, deferredStart,
  checkMood, availableMinutes, checkSubmitted, showCheck,
  focusTask, focusRunning, focusClock,
  completedTaskCount, remainingCompletions, dailyLimitReached,
  suggestedPlan, recommendedTasks,
  load, prepare, canActOn, canComplete, applyCheck, startFocus, toggleFocus, closeFocus,
  act, confirmAction, finishFocus, reverse,
} = useTodayLogic({ onCelebrate: publicId => bridge?.emit({ type: 'celebrate', publicId }) })

useDialogFocus(() => Boolean(selected.value || focusTask.value), '.today-panel-dialog', () => { selected.value = null; closeFocus() })
</script>

<template>
  <section class="world-panel today-panel">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="feedback" class="feedback-banner" :data-tone="feedback.tone" role="status" aria-live="polite">
      <Sparkles :size="16" />
      <p>{{ feedback.text }}<strong v-if="feedback.experience">{{ feedback.experience }}</strong></p>
    </div>

    <div class="panel-toolbar">
      <button class="rhythm-toggle" type="button" :aria-expanded="showCheck" aria-controls="panel-daily-rhythm" @click="showCheck = !showCheck">
        <BatteryMedium :size="14" />调整节奏
      </button>
      <button v-if="last" class="rhythm-toggle" type="button" @click="reverse"><Undo2 :size="14" />撤销上次</button>
    </div>
    <section v-show="showCheck" id="panel-daily-rhythm" class="daily-check">
      <div class="mood-control" aria-label="今日精力">
        <button type="button" :aria-pressed="checkMood === 'low'" @click="checkMood = 'low'">偏低</button>
        <button type="button" :aria-pressed="checkMood === 'steady'" @click="checkMood = 'steady'">稳定</button>
        <button type="button" :aria-pressed="checkMood === 'open'" @click="checkMood = 'open'">充足</button>
      </div>
      <label class="minutes-control" for="panel-available-minutes">
        <span>{{ availableMinutes }} 分钟</span>
        <input id="panel-available-minutes" v-model.number="availableMinutes" type="range" min="10" max="90" step="5">
      </label>
      <div class="check-result">
        <strong>{{ suggestedPlan.title }}</strong>
        <p>{{ suggestedPlan.body }}</p>
        <button class="secondary" type="button" @click="applyCheck">{{ checkSubmitted ? '更新建议' : suggestedPlan.action }}</button>
      </div>
    </section>

    <div v-if="!loading && tasks.length" class="quota" :data-limit-reached="dailyLimitReached" aria-live="polite">
      <span>今日完成额度 <strong>{{ completedTaskCount }} / {{ DAILY_COMPLETION_LIMIT }}</strong></span>
      <progress :value="Math.min(completedTaskCount, DAILY_COMPLETION_LIMIT)" :max="DAILY_COMPLETION_LIMIT" :aria-label="`今日已完成 ${completedTaskCount} 个任务，最多 ${DAILY_COMPLETION_LIMIT} 个`" />
      <p v-if="dailyLimitReached">今日额度已用完，未完成的任务可以延期或留待明天。</p>
      <p v-else>还可以完成 {{ remainingCompletions }} 个任务。</p>
    </div>

    <p v-if="loading" class="empty">正在整理今天的安排…</p>
    <template v-else-if="tasks.length">
      <ul v-if="recommendedTasks.length" class="task-list">
        <li v-for="task in recommendedTasks" :key="task.publicId" class="task-row" :data-task-id="task.publicId">
          <div class="task-main">
            <span class="status">{{ taskStatusLabel(task.status) }}<template v-if="task.roleName"> · {{ task.roleName }}</template></span>
            <strong>{{ task.taskTitle }}</strong>
            <time>{{ new Date(task.plannedStartAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}<template v-if="task.estimatedMinutes"> · 约 {{ task.estimatedMinutes }} 分钟</template></time>
          </div>
          <div class="task-actions">
            <button v-if="task.status === 'PLANNED'" class="icon-button" title="开始" aria-label="开始" :disabled="!canActOn(task)" @click="act(task, 'STARTED')">
              <Play :size="15" />
            </button>
            <button class="primary" :title="dailyLimitReached ? '今日完成额度已用完' : '完成'" aria-label="完成" :disabled="!canComplete(task)" @click="act(task, 'COMPLETED')">
              <Check :size="15" />完成
            </button>
            <details class="task-more">
              <summary aria-label="更多任务操作">更多</summary>
              <div>
                <button class="secondary" :disabled="!canActOn(task)" @click="startFocus(task)"><TimerReset :size="14" />专注执行</button>
                <button class="secondary" :disabled="!canActOn(task)" @click="prepare(task, 'PARTIAL')"><Gauge :size="14" />部分完成</button>
                <button class="secondary" :disabled="!canActOn(task)" @click="prepare(task, 'DEFERRED')"><Clock3 :size="14" />延期</button>
                <button v-if="task.status === 'PLANNED'" class="secondary" :disabled="!canActOn(task)" @click="act(task, 'SKIPPED')"><SkipForward :size="14" />跳过</button>
              </div>
            </details>
          </div>
        </li>
      </ul>
      <p v-else class="empty">今天安排的任务都已经处理过了，歇一歇也很好。</p>
    </template>
    <div v-else class="empty">
      <p>今天还没有安排任务。</p>
    </div>

    <footer class="panel-footer">
      <button class="icon-button" type="button" aria-label="刷新" @click="load(false)"><RefreshCw :size="15" /></button>
      <button class="secondary" type="button" @click="openFullPage(bridge, FULL_PAGE)">打开完整页面</button>
    </footer>

    <Teleport to="body">
      <div v-if="selected || focusTask" class="dialog-backdrop" @click="selected = null; closeFocus()" />
      <section v-if="selected" class="action-panel today-panel-dialog" role="dialog" aria-modal="true" tabindex="-1" :aria-label="selected.eventType === 'PARTIAL' ? '记录部分完成' : '选择延期时间'">
        <template v-if="selected.eventType === 'PARTIAL'">
          <label for="panel-completion">完成比例：{{ completionPercent }}%</label>
          <input id="panel-completion" v-model.number="completionPercent" type="range" min="10" max="90" step="10">
        </template>
        <template v-else>
          <label for="panel-deferred">新的开始时间</label>
          <input id="panel-deferred" v-model="deferredStart" type="datetime-local" required>
        </template>
        <div class="actions">
          <button class="primary" @click="confirmAction">确认记录</button>
          <button class="secondary" @click="selected = null">取消</button>
        </div>
      </section>

      <section v-if="focusTask" class="focus-panel today-panel-dialog" role="dialog" aria-modal="true" tabindex="-1" aria-labelledby="panel-focus-title">
        <button class="icon-button close-focus" type="button" aria-label="关闭专注模式" @click="closeFocus"><X :size="16" /></button>
        <p class="eyebrow">专注执行模式</p>
        <h2 id="panel-focus-title">{{ focusTask.taskTitle }}</h2>
        <div class="focus-clock" aria-live="polite">{{ focusClock }}</div>
        <div class="actions focus-actions">
          <button type="button" class="secondary" @click="toggleFocus">{{ focusRunning ? '暂停' : '开始' }}</button>
          <button type="button" class="primary" :disabled="dailyLimitReached" @click="finishFocus('COMPLETED')">完成</button>
          <button type="button" class="secondary" @click="finishFocus('PARTIAL')">部分完成</button>
        </div>
      </section>
    </Teleport>
  </section>
</template>

<style scoped>
.world-panel { display: grid; gap: 12px; width: 100%; color: var(--ink); }
.panel-toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.rhythm-toggle { display: inline-flex; align-items: center; gap: 5px; background: transparent; color: var(--muted); font-size: 12px; padding: 0; border: 0; }
.daily-check { display: grid; gap: 8px; padding: 10px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 88%, transparent); }
.mood-control { display: grid; grid-template-columns: repeat(3, 1fr); overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius); }
.mood-control button { border: 0; border-right: 1px solid var(--border); border-radius: 0; background: var(--surface); color: var(--muted); font-size: 12px; min-height: 28px; }
.mood-control button:last-child { border-right: 0; }
.mood-control button[aria-pressed='true'] { background: var(--primary); color: white; font-weight: 700; }
.minutes-control { display: grid; gap: 4px; color: var(--muted); font-size: 12px; }
.minutes-control input { width: 100%; }
.check-result { display: grid; gap: 4px; padding-top: 4px; border-top: 1px solid var(--border); font-size: 12px; }
.check-result p { margin: 0; color: var(--muted); line-height: 1.5; }
.check-result button { justify-self: start; margin-top: 2px; }
.quota { display: grid; gap: 6px; font-size: 12px; color: var(--muted); }
.quota progress { width: 100%; height: 6px; }
.quota[data-limit-reached='true'] { color: var(--amber); }
.task-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; max-height: 320px; overflow-y: auto; }
.task-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); }
.task-main { min-width: 0; display: grid; gap: 2px; }
.task-main strong { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-main time { font-size: 11px; color: var(--muted); }
.task-actions { display: flex; align-items: center; gap: 6px; flex: none; }
.task-actions .icon-button { width: 30px; height: 30px; min-width: 30px; min-height: 30px; }
.task-actions .primary { min-height: 30px; padding: 0 10px; font-size: 12px; }
.task-more { position: relative; }
.task-more summary { list-style: none; cursor: pointer; padding: 0 8px; min-height: 30px; display: inline-flex; align-items: center; border: 1px solid var(--border); border-radius: var(--radius); font-size: 12px; color: var(--muted); }
.task-more summary::-webkit-details-marker { display: none; }
.task-more > div { position: absolute; right: 0; top: calc(100% + 4px); z-index: 5; display: grid; gap: 4px; padding: 6px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-raised); box-shadow: var(--shadow-soft); min-width: 128px; }
.task-more button { font-size: 12px; justify-content: flex-start; min-height: 28px; }
.panel-footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.action-panel, .focus-panel { position: fixed; z-index: 60; left: 50%; top: 50%; transform: translate(-50%, -50%); width: min(320px, calc(100vw - 24px)); display: grid; gap: 14px; padding: 20px; border: 1px solid var(--border); border-radius: calc(var(--radius) + 4px); background: var(--surface); box-shadow: var(--shadow); }
.action-panel input { width: 100%; }
.focus-panel { text-align: center; }
.close-focus { position: absolute; top: 10px; right: 10px; border: 1px solid var(--border); }
.focus-panel h2 { margin: 0; font-size: 17px; }
.focus-clock { font-size: 34px; font-weight: 900; color: var(--primary); }
.focus-actions { justify-content: center; flex-wrap: wrap; }
</style>
