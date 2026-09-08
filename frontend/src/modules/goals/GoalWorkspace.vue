<script setup lang="ts">
import { CalendarClock, ChevronLeft, ChevronRight, Clock3, ListPlus, Pause, Play, Plus, RefreshCw, CheckCircle2 } from 'lucide-vue-next'
import { rruleLabel, roleNames, statusLabel, type Goal, type Task } from './goals.logic'

/**
 * Browsing surface: the goal carousel on the left, the current goal's task
 * list on the right. Selection lives here as a plain index prop so the
 * parent stays the single owner of `goalsCurrent`.
 */
defineProps<{
  goals: Goal[]
  tasks: Task[]
  loading: boolean
  currentGoal: Goal | null
  currentTasks: Task[]
  goalsCurrent: number
  canPrevGoals: boolean
  canNextGoals: boolean
}>()

const emit = defineEmits<{
  prev: []
  next: []
  'open-goal': []
  'open-task': [goalPublicId?: string]
  'set-goal-status': [goal: Goal, action: 'pause' | 'resume' | 'complete']
  'set-task-active': [task: Task, active: boolean]
}>()
</script>

<template>
  <section class="workspace" aria-label="目标任务工作区">
    <div class="goal-column">
      <div class="section-head">
        <div>
          <p class="eyebrow">我的方向</p>
          <h2>目标</h2>
        </div>
        <div class="stack-arrows">
          <button class="icon-button" type="button" aria-label="上一个目标" :disabled="!canPrevGoals" @click="emit('prev')">
            <ChevronLeft :size="17" />
          </button>
          <span class="stack-count" aria-live="polite">{{ goals.length ? goalsCurrent + 1 : 0 }} / {{ goals.length }}</span>
          <button class="icon-button" type="button" aria-label="下一个目标" :disabled="!canNextGoals" @click="emit('next')">
            <ChevronRight :size="17" />
          </button>
        </div>
      </div>

      <p v-if="loading" class="empty">正在加载目标...</p>
      <div v-else-if="goals.length" class="goal-stack">
        <article
          v-for="(goal, index) in goals"
          :key="goal.publicId"
          class="goal-card"
          :class="{ current: index === goalsCurrent }"
          :aria-hidden="index !== goalsCurrent"
        >
          <div class="goal-top">
            <span class="status" :data-status="goal.status">{{ statusLabel(goal.status) }}</span>
            <details v-if="goal.status === 'ACTIVE' || goal.status === 'PAUSED'" class="goal-more">
              <summary aria-label="更多目标操作">更多</summary>
              <div>
                <button v-if="goal.status === 'ACTIVE'" class="secondary" type="button" @click="emit('set-goal-status', goal, 'pause')">
                  <Pause :size="16" />暂停目标
                </button>
                <button v-else class="secondary" type="button" @click="emit('set-goal-status', goal, 'resume')">
                  <Play :size="16" />恢复目标
                </button>
                <button class="secondary" type="button" @click="emit('set-goal-status', goal, 'complete')">
                  <CheckCircle2 :size="16" />标记为完成
                </button>
              </div>
            </details>
          </div>
          <h3>{{ goal.title }}</h3>
          <p>{{ goal.description || '尚未填写完成标准' }}</p>
          <div class="goal-footer">
            <span><CalendarClock :size="15" />{{ goal.startDate }} 至 {{ goal.endDate }}</span>
            <span>{{ tasks.filter(task => task.goalPublicId === goal.publicId && task.active).length }} 个进行中任务</span>
          </div>
          <button v-if="goal.status === 'ACTIVE'" class="secondary add-goal-task" type="button" @click="emit('open-task', goal.publicId)">
            <ListPlus :size="16" />
            添加任务
          </button>
        </article>
      </div>
      <div v-else class="empty goal-empty">
        <h3>还没有目标</h3>
        <p>先定义一个 14-84 天的清晰结果。</p>
        <button class="primary" type="button" @click="emit('open-goal')"><Plus :size="17" />新建目标</button>
      </div>
    </div>

    <div class="task-column">
      <div class="section-head">
        <div>
          <p class="eyebrow">当前目标</p>
          <h2>{{ currentGoal ? currentGoal.title : '待添加任务' }}</h2>
          <p v-if="currentGoal" class="task-column-note">完成标准：{{ currentGoal.description || '尚未填写' }}</p>
        </div>
        <span v-if="currentGoal" class="task-count">{{ currentTasks.length }} 项</span>
      </div>

      <div v-if="currentGoal && currentTasks.length" class="task-list">
        <article v-for="task in currentTasks" :key="task.publicId" class="task-row" :class="{ paused: !task.active }">
          <div class="task-row-main">
            <div class="task-row-title">
              <span class="status">{{ task.active ? '进行中' : '已暂停' }}</span>
              <h3>{{ task.title }}</h3>
            </div>
            <button
              class="secondary task-state-button"
              type="button"
              :title="task.active ? '暂停任务' : '恢复任务'"
              @click="emit('set-task-active', task, !task.active)"
            >
              <Pause v-if="task.active" :size="16" />
              <Play v-else :size="16" />
              {{ task.active ? '暂停' : '恢复' }}
            </button>
          </div>
          <p v-if="task.notes">{{ task.notes }}</p>
          <div class="task-meta">
            <span><RefreshCw :size="14" />{{ rruleLabel(task.rrule) }}</span>
            <span><Clock3 :size="14" />{{ task.plannedLocalTime.slice(0, 5) }} · {{ task.estimatedMinutes }} 分钟</span>
            <span><CalendarClock :size="14" />{{ task.activeFrom }} 至 {{ task.activeUntil }}</span>
            <span>{{ roleNames[task.roleCode] ?? '日常' }} · 难度 {{ task.difficulty }}</span>
          </div>
        </article>
      </div>
      <div v-else-if="currentGoal" class="empty task-empty">
        <CalendarClock :size="24" />
        <h3>这个目标还没有任务</h3>
        <button v-if="currentGoal.status === 'ACTIVE'" class="secondary" type="button" @click="emit('open-task', currentGoal.publicId)">
          <ListPlus :size="16" />
          添加任务
        </button>
      </div>
      <p v-else class="empty">创建目标后即可添加任务。</p>
    </div>
  </section>
</template>

<style scoped>
.workspace { display: grid; grid-template-columns: minmax(0, .9fr) minmax(380px, 1.25fr); gap: 24px; padding: 0 0 28px; }
.goal-column, .task-column { min-width: 0; padding: 22px; border: 1px solid var(--border); background: var(--surface); border-radius: var(--radius-panel); }
.goal-column { background: var(--surface-muted); }
.section-head, .goal-top, .task-row-main, .goal-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.section-head h2 { margin: 0; font-size: 18px; }
.goal-more { position: relative; }
.goal-more summary { display: grid; place-items: center; min-width: 62px; height: 36px; padding: 0 12px; border: 1px solid var(--border); border-radius: var(--radius); cursor: pointer; list-style: none; font-size: 13px; font-weight: 650; color: var(--muted); }
.goal-more summary::-webkit-details-marker { display: none; }
.goal-more summary::marker { content: ''; }
.goal-more > div { position: absolute; right: 0; top: 40px; z-index: 8; width: 168px; padding: 6px; display: grid; gap: 4px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow); }
.goal-more > div button { justify-content: flex-start; border: 0; min-height: 38px; }
.task-column-note { margin: 6px 0 0; color: var(--muted); font-size: 13px; line-height: 1.6; }
.task-state-button { min-height: 36px; padding: 0 12px; font-size: 13px; flex: none; border: 1px solid var(--border); }
.task-count { color: var(--muted); font-size: 13px; white-space: nowrap; }
.stack-arrows { display: flex; align-items: center; gap: 6px; }
.stack-arrows .icon-button { border: 1px solid var(--border); }
.stack-count { min-width: 46px; text-align: center; color: var(--muted); font-size: 12px; font-weight: 700; }
.goal-stack { margin-top: 12px; }
.goal-card { display: none; min-height: 310px; padding: 18px; border: 1px solid var(--border); border-radius: var(--radius-panel); background: var(--surface); box-shadow: none; }
.goal-card.current { display: block; }
.goal-card h3 { margin: 18px 0 8px; font-size: 19px; line-height: 1.4; overflow-wrap: anywhere; }
.goal-card > p { min-height: 76px; margin: 0; color: var(--muted); line-height: 1.65; overflow-wrap: anywhere; }
.goal-footer { align-items: flex-start; margin-top: 16px; padding-top: 13px; border-top: 1px solid var(--surface-muted); color: var(--muted); font-size: 12px; }
.goal-footer span:first-child, .task-meta span { display: inline-flex; align-items: center; gap: 5px; }
.add-goal-task { width: 100%; margin-top: 18px; }
.status[data-status='PAUSED'], .task-row.paused .status { color: var(--amber); }
.status[data-status='COMPLETED'] { color: var(--accent); }
.task-list { display: grid; gap: 10px; margin-top: 12px; }
.task-row { padding: 14px 15px; border: 1px solid var(--border); border-left: 3px solid var(--accent); border-radius: var(--radius); background: var(--surface); box-shadow: 0 5px 16px rgb(71 54 44 / 6%); }
.task-row.paused { border-left-color: var(--amber); opacity: .76; }
.task-row-title { min-width: 0; }
.task-row h3 { margin: 4px 0 0; font-size: 16px; line-height: 1.4; overflow-wrap: anywhere; }
.task-row > p { margin: 9px 0 0; color: var(--muted); font-size: 13px; line-height: 1.55; overflow-wrap: anywhere; }
.task-meta { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px 14px; margin-top: 13px; padding-top: 11px; border-top: 1px solid var(--surface-muted); color: var(--muted); font-size: 12px; }
.task-meta svg { flex: none; color: var(--accent); }
.task-empty, .goal-empty { display: grid; justify-items: center; gap: 8px; margin-top: 12px; border: 1px dashed var(--border); border-radius: var(--radius); padding: 38px 18px; }
.task-empty h3, .goal-empty h3 { margin: 0; color: var(--ink); font-size: 16px; }
.task-empty svg { color: var(--accent); }
@media (prefers-reduced-motion: no-preference) {
  .goal-card.current, .task-row { animation: item-enter var(--motion-medium) ease-out both; }
}
@keyframes item-enter { from { opacity: 0; transform: translateY(7px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 900px) {
  .workspace { grid-template-columns: 1fr; }
  .goal-card { min-height: 290px; }
}
@media (max-width: 760px) {
  .goal-column, .task-column { padding: 16px; }
}
@media (max-width: 520px) {
  .goal-card { min-height: 360px; }
}
</style>
