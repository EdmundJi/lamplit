<script setup lang="ts">
import { Check, RefreshCw } from 'lucide-vue-next'
import SegmentedControl from '../../shared/ui/interaction/SegmentedControl.vue'
import SnapSlider from '../../shared/ui/interaction/SnapSlider.vue'
import { roleNames, roles, rruleLabel, rruleOptions, type Goal, type Preset, type PresetDraw, type TaskForm } from './goals.logic'

/**
 * The "add periodic task" drawer: role tabs, preset picker and the raw task
 * fields. `taskForm` is edited in place; the parent owns loading presets,
 * saving and everything about which goal is active.
 */
defineProps<{
  goals: Goal[]
  activeGoals: Goal[]
  taskForm: TaskForm
  selectedRole: string
  presetDraw: PresetDraw | null
  presetsLoading: boolean
  presetError: string
  busy: boolean
}>()

const emit = defineEmits<{
  submit: []
  cancel: []
  'choose-role': [role: typeof roles[number]]
  'choose-preset': [preset: Preset]
  'refresh-presets': []
  'sync-task-period': []
}>()

const roleOptions = roles.map(role => ({ value: role.code, label: role.name }))
function chooseRoleByCode(code: string) {
  const role = roles.find(item => item.code === code)
  if (role) emit('choose-role', role)
}
</script>

<template>
  <form class="band stack task-builder goal-drawer" role="dialog" aria-modal="true" aria-label="添加周期任务" tabindex="-1" @submit.prevent="emit('submit')">
    <button type="button" class="drawer-close secondary" :disabled="busy" @click="emit('cancel')" aria-label="关闭任务编辑">关闭</button>
    <div class="task-builder-head">
      <div>
        <p class="eyebrow">周期任务</p>
        <h2>添加任务</h2>
      </div>
      <span v-if="presetDraw" class="refresh-quota">今日还可换 {{ presetDraw.refreshesRemaining }} 次</span>
    </div>

    <SegmentedControl
      class="role-tabs"
      :model-value="selectedRole"
      :options="roleOptions"
      label="选择任务场景"
      @update:model-value="value => chooseRoleByCode(value)"
    />
    <div class="preset-toolbar">
      <strong>{{ roleNames[selectedRole] }}任务模板</strong>
      <button
        class="secondary refresh-button"
        type="button"
        :disabled="presetsLoading || !presetDraw?.refreshesRemaining"
        @click="emit('refresh-presets')"
      >
        <RefreshCw :size="16" :class="{ spinning: presetsLoading }" />
        换一批
      </button>
    </div>
    <p v-if="presetError" class="error compact-error" role="alert">{{ presetError }}</p>
    <p v-if="presetsLoading && !presetDraw" class="preset-loading">正在准备任务模板...</p>
    <div v-else-if="presetDraw" class="preset-list">
      <button
        v-for="preset in presetDraw.items"
        :key="preset.publicId"
        type="button"
        class="preset-item"
        :class="{ selected: taskForm.sourceTemplatePublicId === preset.publicId }"
        :aria-pressed="taskForm.sourceTemplatePublicId === preset.publicId"
        @click="emit('choose-preset', preset)"
      >
        <span class="preset-main">
          <span class="preset-title">
            <Check v-if="taskForm.sourceTemplatePublicId === preset.publicId" :size="16" />
            {{ preset.name }}
          </span>
          <small>{{ preset.notes }}</small>
        </span>
        <span class="preset-meta">
          <span>{{ preset.estimatedMinutes }} 分钟</span>
          <span>难度 {{ preset.difficulty }}</span>
          <span>{{ preset.plannedLocalTime.slice(0, 5) }}</span>
          <span>{{ rruleLabel(preset.rrule) }}</span>
          <b>+{{ preset.experienceReward }} 经验</b>
        </span>
      </button>
    </div>

    <div class="task-fields">
      <div class="form-grid task-identity-grid">
        <div class="field">
          <label for="task-goal">所属目标</label>
          <select id="task-goal" v-model="taskForm.goalPublicId" required @change="emit('sync-task-period')">
            <option v-for="goal in activeGoals" :key="goal.publicId" :value="goal.publicId">{{ goal.title }}</option>
          </select>
        </div>
        <div class="field">
          <label for="task-title">任务名称</label>
          <input id="task-title" v-model="taskForm.title" required maxlength="160">
        </div>
      </div>
      <div class="form-grid task-property-grid">
        <div class="field">
          <label for="task-rule">重复周期</label>
          <select id="task-rule" v-model="taskForm.rrule">
            <option v-for="option in rruleOptions" :key="option[0]" :value="option[0]">{{ option[1] }}</option>
          </select>
        </div>
        <div class="field">
          <label for="task-time">计划时间</label>
          <input id="task-time" v-model="taskForm.plannedLocalTime" type="time" required>
        </div>
        <div class="field">
          <label for="task-minutes">预计分钟</label>
          <input id="task-minutes" v-model.number="taskForm.estimatedMinutes" type="number" min="5" max="240" required>
        </div>
        <div class="field">
          <label for="task-difficulty">难度（1-3）· {{ taskForm.difficulty }}</label>
          <SnapSlider id="task-difficulty" v-model="taskForm.difficulty" :min="1" :max="3" :step="1" :value-text="`难度 ${taskForm.difficulty}`" />
        </div>
      </div>
      <div class="form-grid two-columns">
        <div class="field">
          <label for="task-start">开始日期</label>
          <input
            id="task-start"
            v-model="taskForm.activeFrom"
            type="date"
            :min="goals.find(goal => goal.publicId === taskForm.goalPublicId)?.startDate"
            :max="taskForm.activeUntil"
            required
          >
        </div>
        <div class="field">
          <label for="task-end">结束日期</label>
          <input
            id="task-end"
            v-model="taskForm.activeUntil"
            type="date"
            :min="taskForm.activeFrom"
            :max="goals.find(goal => goal.publicId === taskForm.goalPublicId)?.endDate"
            required
          >
        </div>
      </div>
      <div class="field">
        <label for="task-notes">备注</label>
        <textarea id="task-notes" v-model="taskForm.notes" />
      </div>
    </div>
    <div class="actions">
      <button class="primary" :disabled="busy">保存并排入日程</button>
      <button class="secondary" type="button" @click="emit('cancel')">取消</button>
    </div>
  </form>
</template>

<style scoped>
.task-builder { gap: 16px; margin-bottom: 28px; padding: 28px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-panel); box-shadow: var(--shadow); }
.task-builder h2 { margin: 0; font-size: 18px; }
.task-builder-head, .preset-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.refresh-quota { color: var(--muted); font-size: 13px; white-space: nowrap; }
.refresh-button { flex: none; }
.preset-list { display: grid; gap: 8px; }
.preset-item { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 18px; min-height: 76px; padding: 12px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); text-align: left; }
.preset-item:hover { border-color: color-mix(in srgb, var(--primary) 34%, var(--border)); background: var(--surface-raised); }
.preset-item.selected { border-color: color-mix(in srgb, var(--accent) 55%, var(--border)); background: color-mix(in srgb, var(--accent) 8%, var(--surface)); box-shadow: inset 3px 0 var(--accent); }
.preset-main { display: grid; gap: 5px; min-width: 0; }
.preset-title { display: flex; align-items: center; gap: 7px; font-weight: 700; }
.preset-title svg { flex: none; color: var(--accent); }
.preset-main small { overflow-wrap: anywhere; color: var(--muted); line-height: 1.45; }
.preset-meta { display: grid; grid-template-columns: repeat(2, auto); gap: 4px 12px; color: var(--muted); font-size: 12px; text-align: right; }
.preset-meta b { color: var(--amber); }
.task-fields { display: grid; gap: 14px; padding-top: 4px; }
.compact-error { margin: 0; }
.preset-loading { padding: 24px 0; text-align: center; color: var(--muted); }
.form-grid { display: grid; gap: 14px; }
.two-columns, .task-identity-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.task-property-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.spinning { animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: no-preference) {
  .preset-item { animation: item-enter var(--motion-medium) var(--ease) both; }
}
@keyframes item-enter { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
.goal-drawer { position: fixed; inset: 16px 16px 16px auto; z-index: 51; width: min(760px, calc(100vw - 32px)); max-height: calc(100dvh - 32px); overflow-y: auto; margin: 0; align-content: start; }
.drawer-close { justify-self: end; }
@media (max-width: 980px) {
  .task-property-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .task-builder { padding: 18px; }
  .goal-drawer { inset: 8px; width: calc(100vw - 16px); max-height: calc(100dvh - 16px); }
}
@media (max-width: 700px) {
  .two-columns, .task-identity-grid, .task-property-grid { grid-template-columns: 1fr; }
  .preset-item { grid-template-columns: 1fr; }
  .preset-meta { justify-content: start; text-align: left; }
}
@media (max-width: 520px) {
  .task-builder-head, .preset-toolbar { align-items: flex-start; flex-direction: column; gap: 7px; }
  .task-builder > .actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .task-builder > .actions button, .refresh-button { width: 100%; min-width: 0; }
}
</style>
