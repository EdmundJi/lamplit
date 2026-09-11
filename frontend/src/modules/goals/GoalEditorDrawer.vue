<script setup lang="ts">
import { Sparkles } from 'lucide-vue-next'
import SnapSlider from '../../shared/ui/interaction/SnapSlider.vue'
import type { GoalDraftTask } from '../ai/goal-draft'
import type { Dimension, GoalForm } from './goals.logic'

/**
 * The "new / edit goal" drawer: dimension, title, completion criteria, dates
 * and — when an AI draft supplied them — the editable starter-task list.
 * Owns none of the goal state; it edits `goalForm` in place and lets the
 * parent decide what saving and cancelling mean.
 */
defineProps<{
  dimensions: Dimension[]
  goalForm: GoalForm
  busy: boolean
  goalPrompt: string
  aiStarterTasks: GoalDraftTask[]
}>()

const emit = defineEmits<{ submit: []; cancel: [] }>()
</script>

<template>
  <form class="band stack editor goal-drawer" role="dialog" aria-modal="true" aria-label="新建目标" tabindex="-1" @submit.prevent="emit('submit')">
    <button type="button" class="drawer-close secondary" :disabled="busy" @click="emit('cancel')" aria-label="关闭目标编辑">关闭</button>
    <div>
      <h2>新建目标</h2>
      <p class="support-line"><Sparkles :size="16" />{{ goalPrompt }}</p>
    </div>
    <div class="field">
      <label for="dimension">成长维度</label>
      <select id="dimension" v-model="goalForm.dimensionPublicId" required>
        <option v-for="dimension in dimensions" :key="dimension.publicId" :value="dimension.publicId">{{ dimension.name }}</option>
      </select>
    </div>
    <div class="field">
      <label for="goal-title">目标名称</label>
      <input id="goal-title" v-model="goalForm.title" required maxlength="160">
    </div>
    <div class="field">
      <label for="goal-description">完成标准</label>
      <textarea id="goal-description" v-model="goalForm.description" />
    </div>
    <div class="form-grid two-columns">
      <div class="field">
        <label for="goal-start">开始日期</label>
        <input id="goal-start" v-model="goalForm.startDate" type="date" required>
      </div>
      <div class="field">
        <label for="goal-end">结束日期</label>
        <input id="goal-end" v-model="goalForm.endDate" type="date" required>
      </div>
    </div>
    <fieldset v-if="aiStarterTasks.length" class="ai-starter-tasks">
      <legend>起步任务（{{ aiStarterTasks.length }} 项）</legend>
      <div v-for="(task, index) in aiStarterTasks" :key="index" class="ai-starter-task">
        <label>
          <span class="sr-only">起步任务 {{ index + 1 }} 名称</span>
          <input v-model="task.title" :disabled="busy" maxlength="160" required>
        </label>
        <label>
          <span class="sr-only">起步任务 {{ index + 1 }} 预计分钟</span>
          <SnapSlider v-model="task.estimatedMinutes" :disabled="busy" :min="5" :max="60" :step="5" :value-text="`${task.estimatedMinutes} 分钟`" />
        </label>
        <label>
          <span class="sr-only">起步任务 {{ index + 1 }} 难度</span>
          <SnapSlider v-model="task.difficulty" :disabled="busy" :min="1" :max="3" :step="1" :value-text="`难度 ${task.difficulty}`" />
        </label>
      </div>
    </fieldset>
    <div class="actions">
      <button class="primary" :disabled="busy">{{ busy ? '正在保存…' : '保存目标' }}</button>
      <button class="secondary" type="button" :disabled="busy" @click="emit('cancel')">取消</button>
    </div>
  </form>
</template>

<style scoped>
.editor { gap: 16px; margin-bottom: 28px; padding: 28px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-panel); box-shadow: var(--shadow); }
.editor h2 { margin: 0; font-size: 18px; }
.support-line { display: flex; align-items: flex-start; gap: 8px; max-width: 680px; margin: 7px 0 0; color: var(--primary); line-height: 1.65; }
.support-line svg { flex: none; margin-top: 4px; }
.ai-starter-tasks { display: grid; gap: 9px; margin: 0; padding: 13px; border: 1px solid color-mix(in srgb, var(--primary) 24%, var(--border)); border-radius: var(--radius); background: color-mix(in srgb, var(--primary-soft) 34%, var(--surface)); }
.ai-starter-tasks legend { padding: 0 5px; color: var(--primary-strong); font-size: 14px; font-weight: 750; }
.ai-starter-task { display: grid; grid-template-columns: minmax(0, 1fr) 84px 100px; gap: 8px; }
.ai-starter-task label, .ai-starter-task input, .ai-starter-task select { min-width: 0; }
.ai-starter-task input, .ai-starter-task select { width: 100%; min-height: var(--control); border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); padding: 9px 10px; }
.ai-starter-task input:hover, .ai-starter-task select:hover { border-color: color-mix(in srgb, var(--primary) 30%, var(--border)); }
.form-grid { display: grid; gap: 14px; }
.two-columns { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; }
.goal-drawer { position: fixed; inset: 16px 16px 16px auto; z-index: 51; width: min(760px, calc(100vw - 32px)); max-height: calc(100dvh - 32px); overflow-y: auto; margin: 0; align-content: start; }
.drawer-close { justify-self: end; }
@media (max-width: 760px) {
  .editor { padding: 18px; }
  .goal-drawer { inset: 8px; width: calc(100vw - 16px); max-height: calc(100dvh - 16px); }
}
@media (max-width: 700px) {
  .two-columns { grid-template-columns: 1fr; }
  .ai-starter-task { grid-template-columns: minmax(0, 1fr) 84px; }
  .ai-starter-task label:last-child { grid-column: 1 / -1; }
}
@media (max-width: 520px) {
  .editor > .actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .editor > .actions button { width: 100%; min-width: 0; }
}
</style>
