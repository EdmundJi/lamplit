<script setup lang="ts">
import { onActivated, onDeactivated } from 'vue'
import { inject, onBeforeUnmount, ref } from 'vue'
import { CheckCircle2, ListPlus, Pause, Pencil, Play, Plus, RefreshCw, Sparkles, Target, X } from 'lucide-vue-next'
import { useDialogFocus } from '../../../../shared/ui/use-dialog-focus'
import { worldBridgeKey } from '../panel.types'
import { openFullPage } from './shared'
import { onGoalDraftChanged, peekGoalDraft, takeGoalDraft } from '../../../ai/goal-draft'
import AiDraftNotice from './AiDraftNotice.vue'
import { goalTemplates, roleNames, roles, rruleLabel, rruleOptions, statusLabel, useGoalsLogic } from '../../../goals/goals.logic'

const FULL_PAGE = '/goals'

const bridge = inject(worldBridgeKey, undefined)
function beginToday() { bridge?.emit({ type: 'close' }); bridge?.emit({ type: 'open', panel: 'today' }) }

const {
  goals, dimensions, tasks, loading, panel, error, busy, feedback, goalPrompt,
  selectedRole, presetDraw, presetsLoading, presetError,
  aiStarterTasks, editingGoalPublicId, editingTaskPublicId,
  goalForm, taskForm,
  activeGoals, visibleGoals,
  openGoal, openGoalForEdit, cancelGoal, syncTaskPeriod,
  openTask, openTaskForEdit, loadPresets, chooseRole, choosePreset,
  applyGoalTemplate, applyAiGoalDraft,
  createGoal, createTask, setTaskActive, setGoalStatus,
} = useGoalsLogic({ onCelebrate: publicId => bridge?.emit({ type: 'celebrate', publicId }) })

const pendingAiDraft = ref(peekGoalDraft())
const stopDraftNotice = onGoalDraftChanged(() => { pendingAiDraft.value = peekGoalDraft() })
onBeforeUnmount(stopDraftNotice)
function loadPendingAiDraft() {
  if (panel.value || busy.value || loading.value) return
  const next = takeGoalDraft()
  if (next) applyAiGoalDraft(next)
}

function tasksOf(goalPublicId: string) {
  return tasks.value.filter(task => task.goalPublicId === goalPublicId)
}

const props = withDefaults(defineProps<{ active?: boolean }>(), { active: true })
const deactivated = ref(false)
onActivated(() => { deactivated.value = false })
onDeactivated(() => { deactivated.value = true })
useDialogFocus(() => props.active && !deactivated.value && panel.value !== null, '.goals-panel-drawer', () => { if (!busy.value) panel.value = null })
</script>

<template>
  <section class="world-panel goals-panel">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="feedback" class="feedback-banner" :data-tone="feedback.tone" role="status" aria-live="polite">
      <CheckCircle2 :size="16" />
      <p>{{ feedback.text }}</p>
      <button v-if="bridge && !error && !panel && !busy" class="secondary start-today" type="button" @click="beginToday">去今天开始</button>
    </div>

    <AiDraftNotice v-if="pendingAiDraft && !panel" :title="pendingAiDraft.title" :editing="false" :disabled="busy || loading" @load="loadPendingAiDraft" />

    <div v-if="panel" class="dialog-backdrop" @click="!busy && cancelGoal()" />
    <form v-if="panel === 'goal'" class="drawer goals-panel-drawer" role="dialog" aria-modal="true" aria-label="目标编辑" tabindex="-1" @submit.prevent="createGoal">
      <AiDraftNotice v-if="pendingAiDraft" :title="pendingAiDraft.title" :editing="true" :disabled="true" />
      <div class="drawer-head">
        <strong>{{ editingGoalPublicId ? '编辑目标' : '新建目标' }}</strong>
        <button type="button" class="icon-button" :disabled="busy" aria-label="关闭" @click="cancelGoal"><X :size="15" /></button>
      </div>
      <p class="support-line"><Sparkles :size="14" />{{ goalPrompt }}</p>
      <div v-if="!editingGoalPublicId" class="template-chips" aria-label="从模板开始">
        <button v-for="template in goalTemplates" :key="template.roleCode" type="button" class="chip" @click="applyGoalTemplate(template)">{{ roleNames[template.roleCode] }}</button>
      </div>
      <div class="field">
        <label for="panel-dimension">成长维度</label>
        <select id="panel-dimension" v-model="goalForm.dimensionPublicId" required>
          <option v-for="dimension in dimensions" :key="dimension.publicId" :value="dimension.publicId">{{ dimension.name }}</option>
        </select>
      </div>
      <div class="field">
        <label for="panel-goal-title">目标名称</label>
        <input id="panel-goal-title" v-model="goalForm.title" required maxlength="160">
      </div>
      <div class="field">
        <label for="panel-goal-description">完成标准</label>
        <textarea id="panel-goal-description" v-model="goalForm.description" rows="2" />
      </div>
      <div class="form-grid two-columns">
        <div class="field">
          <label for="panel-goal-start">开始日期</label>
          <input id="panel-goal-start" v-model="goalForm.startDate" type="date" required>
        </div>
        <div class="field">
          <label for="panel-goal-end">结束日期</label>
          <input id="panel-goal-end" v-model="goalForm.endDate" type="date" required>
        </div>
      </div>
      <fieldset v-if="aiStarterTasks.length" class="ai-starter-tasks">
        <legend>起步任务（{{ aiStarterTasks.length }} 项）</legend>
        <div v-for="(task, index) in aiStarterTasks" :key="index" class="ai-starter-task">
          <input v-model="task.title" :disabled="busy" maxlength="160" required :aria-label="`起步任务 ${index + 1} 名称`">
        </div>
      </fieldset>
      <div class="actions">
        <button class="primary" :disabled="busy">{{ busy ? '正在保存…' : '保存目标' }}</button>
        <button class="secondary" type="button" :disabled="busy" @click="cancelGoal">取消</button>
      </div>
    </form>

    <form v-else-if="panel === 'task'" class="drawer goals-panel-drawer" role="dialog" aria-modal="true" aria-label="周期任务编辑" tabindex="-1" @submit.prevent="createTask">
      <AiDraftNotice v-if="pendingAiDraft" :title="pendingAiDraft.title" :editing="true" :disabled="true" />
      <div class="drawer-head">
        <strong>{{ editingTaskPublicId ? '编辑任务' : '添加任务' }}</strong>
        <button type="button" class="icon-button" :disabled="busy" aria-label="关闭" @click="panel = null"><X :size="15" /></button>
      </div>

      <template v-if="!editingTaskPublicId">
        <div class="role-tabs" aria-label="选择任务场景">
          <button v-for="role in roles" :key="role.code" type="button" :class="{ active: selectedRole === role.code }" :aria-pressed="selectedRole === role.code" @click="chooseRole(role)">{{ role.name }}</button>
        </div>
        <div class="preset-toolbar">
          <span v-if="presetDraw">今日还可换 {{ presetDraw.refreshesRemaining }} 次</span>
          <button class="secondary refresh-button" type="button" :disabled="presetsLoading || !presetDraw?.refreshesRemaining" @click="loadPresets(true)">
            <RefreshCw :size="14" :class="{ spinning: presetsLoading }" />换一批
          </button>
        </div>
        <p v-if="presetError" class="error compact-error" role="alert">{{ presetError }}</p>
        <div v-else-if="presetDraw" class="preset-chips">
          <button v-for="preset in presetDraw.items" :key="preset.publicId" type="button" class="chip" :class="{ selected: taskForm.sourceTemplatePublicId === preset.publicId }" :aria-pressed="taskForm.sourceTemplatePublicId === preset.publicId" @click="choosePreset(preset)">{{ preset.name }}</button>
        </div>
        <div class="field">
          <label for="panel-task-goal">所属目标</label>
          <select id="panel-task-goal" v-model="taskForm.goalPublicId" required @change="syncTaskPeriod()">
            <option v-for="goal in activeGoals" :key="goal.publicId" :value="goal.publicId">{{ goal.title }}</option>
          </select>
        </div>
      </template>

      <div class="field">
        <label for="panel-task-title">任务名称</label>
        <input id="panel-task-title" v-model="taskForm.title" required maxlength="160">
      </div>
      <div class="form-grid two-columns">
        <div class="field">
          <label for="panel-task-rule">重复周期</label>
          <select id="panel-task-rule" v-model="taskForm.rrule">
            <option v-for="option in rruleOptions" :key="option[0]" :value="option[0]">{{ option[1] }}</option>
          </select>
        </div>
        <div class="field">
          <label for="panel-task-time">计划时间</label>
          <input id="panel-task-time" v-model="taskForm.plannedLocalTime" type="time" required>
        </div>
        <div class="field">
          <label for="panel-task-minutes">预计分钟</label>
          <input id="panel-task-minutes" v-model.number="taskForm.estimatedMinutes" type="number" min="5" max="240" required>
        </div>
        <div class="field">
          <label for="panel-task-difficulty">难度（1-3）</label>
          <input id="panel-task-difficulty" v-model.number="taskForm.difficulty" type="number" min="1" max="3" required>
        </div>
        <div class="field">
          <label for="panel-task-start">开始日期</label>
          <input id="panel-task-start" v-model="taskForm.activeFrom" type="date" required>
        </div>
        <div class="field">
          <label for="panel-task-end">结束日期</label>
          <input id="panel-task-end" v-model="taskForm.activeUntil" type="date" required>
        </div>
      </div>
      <div class="actions">
        <button class="primary" :disabled="busy">{{ busy ? '正在保存…' : '保存并排入日程' }}</button>
        <button class="secondary" type="button" :disabled="busy" @click="panel = null">取消</button>
      </div>
    </form>

    <p v-if="loading" class="empty">正在整理目标…</p>
    <template v-else-if="visibleGoals.length">
      <ul class="goal-list">
        <li v-for="goal in visibleGoals" :key="goal.publicId" class="goal-row">
          <div class="goal-head">
            <div class="goal-main">
              <span class="status" :data-status="goal.status">{{ statusLabel(goal.status) }}</span>
              <strong>{{ goal.title }}</strong>
            </div>
            <div class="goal-actions">
              <button v-if="goal.status === 'ACTIVE'" class="icon-button" title="暂停" aria-label="暂停" :disabled="busy" @click="setGoalStatus(goal, 'pause')"><Pause :size="14" /></button>
              <button v-else class="icon-button" title="继续" aria-label="继续" :disabled="busy" @click="setGoalStatus(goal, 'resume')"><Play :size="14" /></button>
              <button class="icon-button" title="编辑" aria-label="编辑目标" :disabled="busy" @click="openGoalForEdit(goal)"><Pencil :size="14" /></button>
              <button class="secondary" :disabled="busy" @click="setGoalStatus(goal, 'complete')"><CheckCircle2 :size="14" />完成</button>
            </div>
          </div>
          <details class="goal-tasks">
            <summary>{{ tasksOf(goal.publicId).length }} 个周期任务</summary>
            <ul v-if="tasksOf(goal.publicId).length" class="task-list">
              <li v-for="task in tasksOf(goal.publicId)" :key="task.publicId" class="task-row" :class="{ paused: !task.active }">
                <div class="task-main">
                  <span class="status">{{ task.active ? '进行中' : '已暂停' }}</span>
                  <strong>{{ task.title }}</strong>
                  <small>{{ rruleLabel(task.rrule) }} · {{ task.plannedLocalTime.slice(0, 5) }} · {{ task.estimatedMinutes }} 分钟</small>
                </div>
                <div class="task-actions">
                  <button class="icon-button" :title="task.active ? '暂停任务' : '恢复任务'" :aria-label="task.active ? '暂停任务' : '恢复任务'" @click="setTaskActive(task, !task.active)">
                    <Pause v-if="task.active" :size="13" /><Play v-else :size="13" />
                  </button>
                  <button class="icon-button" title="编辑任务" aria-label="编辑任务" @click="openTaskForEdit(task)"><Pencil :size="13" /></button>
                </div>
              </li>
            </ul>
            <button v-if="goal.status === 'ACTIVE'" class="secondary add-task" type="button" @click="openTask(goal.publicId)"><ListPlus :size="14" />添加任务</button>
          </details>
        </li>
      </ul>
    </template>
    <div v-else class="empty">
      <Target :size="20" />
      <p>还没有进行中的目标，去添加一个想抵达的方向吧。</p>
    </div>

    <footer class="panel-footer">
      <div class="footer-primary">
        <button class="secondary" type="button" @click="openTask()"><ListPlus :size="15" />添加任务</button>
        <button class="primary" type="button" @click="openGoal"><Plus :size="15" />新建目标</button>
      </div>
      <button class="secondary" type="button" @click="openFullPage(bridge, FULL_PAGE)">打开完整页面</button>
    </footer>
  </section>
</template>

<style scoped>
.start-today { grid-column: 2; justify-self: start; padding: 8px 14px; min-height: 34px; font-size: 12px; }
.world-panel { display: grid; gap: 12px; width: 100%; color: var(--ink); }
.goal-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; max-height: 360px; overflow-y: auto; }
.goal-row { display: grid; gap: 8px; padding: 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); }
.goal-head { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; }
.goal-main { min-width: 0; display: grid; gap: 2px; }
.goal-main strong { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status[data-status='PAUSED'] { color: var(--amber); }
.goal-actions { display: flex; align-items: center; gap: 6px; flex: none; }
.goal-actions .icon-button { width: 28px; height: 28px; min-width: 28px; min-height: 28px; }
.goal-actions .secondary { min-height: 28px; padding: 0 8px; font-size: 12px; }
.goal-tasks summary { cursor: pointer; list-style: none; font-size: 12px; color: var(--muted); }
.goal-tasks summary::-webkit-details-marker { display: none; }
.goal-tasks { margin-top: 2px; }
.task-list { list-style: none; margin: 8px 0 0; padding: 0; display: grid; gap: 6px; }
.task-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 8px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-muted); }
.task-row.paused { opacity: .76; }
.task-main { min-width: 0; display: grid; gap: 2px; }
.task-main strong { font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-main small { color: var(--muted); font-size: 11px; }
.task-actions { display: flex; gap: 4px; flex: none; }
.task-actions .icon-button { width: 26px; height: 26px; min-width: 26px; min-height: 26px; }
.add-task { margin-top: 8px; width: 100%; }
.panel-footer { display: grid; gap: 8px; }
.footer-primary { display: flex; gap: 8px; }
.footer-primary button { flex: 1; }
.drawer { position: fixed; z-index: 60; inset: 8px 8px 8px auto; width: min(340px, calc(100vw - 16px)); max-height: calc(100dvh - 16px); overflow-y: auto; display: grid; gap: 10px; padding: 16px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); box-shadow: var(--shadow); }
.drawer-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.drawer-head strong { font-size: 14px; }
.support-line { display: flex; align-items: flex-start; gap: 6px; margin: 0; color: var(--primary); font-size: 12px; line-height: 1.5; }
.support-line svg { flex: none; margin-top: 2px; }
.template-chips, .preset-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { padding: 4px 10px; border: 1px solid var(--border); border-radius: 999px; background: var(--surface); color: var(--muted); font-size: 12px; }
.chip.selected, .chip[aria-pressed='true'] { border-color: color-mix(in srgb, var(--accent) 55%, var(--border)); color: var(--accent); font-weight: 700; }
.field { display: grid; gap: 4px; }
.field label { font-size: 12px; color: var(--muted); }
.form-grid { display: grid; gap: 8px; }
.two-columns { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.role-tabs { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 3px; padding: 3px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-muted); }
.role-tabs button { min-width: 0; border: 0; background: transparent; color: var(--muted); font-size: 11px; padding: 0 2px; min-height: 26px; }
.role-tabs button.active { background: var(--surface); color: var(--primary); }
.preset-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 12px; color: var(--muted); }
.refresh-button { flex: none; font-size: 12px; min-height: 26px; padding: 0 8px; }
.spinning { animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.compact-error { margin: 0; }
.ai-starter-tasks { display: grid; gap: 6px; margin: 0; padding: 8px; border: 1px solid color-mix(in srgb, var(--primary) 24%, var(--border)); border-radius: var(--radius); background: color-mix(in srgb, var(--primary-soft) 34%, var(--surface)); }
.ai-starter-tasks legend { font-size: 12px; color: var(--primary-strong); font-weight: 700; }
.ai-starter-task input { width: 100%; }
.actions { display: flex; gap: 8px; }
.actions button { flex: 1; }
</style>
