<script setup lang="ts">
import { ListPlus, Plus, Sparkles } from 'lucide-vue-next'
import { useDialogFocus } from '../../shared/ui/use-dialog-focus'
import { useGoalsLogic } from './goals.logic'
import GoalEditorDrawer from './GoalEditorDrawer.vue'
import TaskEditorDrawer from './TaskEditorDrawer.vue'
import GoalWorkspace from './GoalWorkspace.vue'
import GoalTemplateBank from './GoalTemplateBank.vue'

const {
  goals, dimensions, tasks, loading, panel, error, busy, feedback, goalPrompt,
  selectedRole, presetDraw, presetsLoading, presetError, goalsCurrent,
  aiStarterTasks,
  goalForm, taskForm,
  currentGoal, currentTasks, activeGoals, canPrevGoals, canNextGoals,
  goalsPrev, goalsNext,
  openGoal, cancelGoal, syncTaskPeriod,
  openTask, loadPresets, chooseRole, choosePreset,
  applyGoalTemplate,
  createGoal, createTask, setTaskActive, setGoalStatus,
} = useGoalsLogic()

useDialogFocus(() => panel.value !== null, '.goal-drawer', () => { if (!busy.value) panel.value = null })
</script>

<template>
  <section class="page goals-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">成长路径</p>
        <h1>目标与任务</h1>
        <p class="page-description">把想去的远方，拆成今天走得到的一步。</p>
      </div>
      <div class="actions">
        <button class="secondary" type="button" @click="openTask()">
          <ListPlus :size="17" />
          添加任务
        </button>
        <button class="primary" type="button" @click="openGoal">
          <Plus :size="17" />
          新建目标
        </button>
      </div>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="feedback" class="feedback-banner" :data-tone="feedback.tone" role="status" aria-live="polite">
      <Sparkles :size="19" />
      <p>{{ feedback.text }}</p>
    </div>

    <div v-if="panel" class="dialog-backdrop" @click="!busy && (panel = null)" />
    <GoalEditorDrawer
      v-if="panel === 'goal'"
      :dimensions="dimensions"
      :goal-form="goalForm"
      :busy="busy"
      :goal-prompt="goalPrompt"
      :ai-starter-tasks="aiStarterTasks"
      @submit="createGoal"
      @cancel="cancelGoal"
    />
    <TaskEditorDrawer
      v-else-if="panel === 'task'"
      :goals="goals"
      :active-goals="activeGoals"
      :task-form="taskForm"
      :selected-role="selectedRole"
      :preset-draw="presetDraw"
      :presets-loading="presetsLoading"
      :preset-error="presetError"
      :busy="busy"
      @submit="createTask"
      @cancel="panel = null"
      @choose-role="chooseRole"
      @choose-preset="choosePreset"
      @refresh-presets="loadPresets(true)"
      @sync-task-period="syncTaskPeriod()"
    />

    <GoalWorkspace
      :goals="goals"
      :tasks="tasks"
      :loading="loading"
      :current-goal="currentGoal"
      :current-tasks="currentTasks"
      :goals-current="goalsCurrent"
      :can-prev-goals="canPrevGoals"
      :can-next-goals="canNextGoals"
      @prev="goalsPrev"
      @next="goalsNext"
      @open-goal="openGoal"
      @open-task="openTask"
      @set-goal-status="setGoalStatus"
      @set-task-active="setTaskActive"
    />

    <GoalTemplateBank @apply="applyGoalTemplate" />
  </section>
</template>

<style scoped>
.goals-page:has(.goal-drawer) { animation: none; }
</style>
