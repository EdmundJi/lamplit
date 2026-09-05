<script setup lang="ts">
import {
  CalendarClock,
  Check,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock3,
  ListPlus,
  Pause,
  Play,
  Plus,
  RefreshCw,
  Sparkles,
} from 'lucide-vue-next'
import { useDialogFocus } from '../../shared/ui/use-dialog-focus'
import { goalTemplates, roleNames, roles, rruleLabel, rruleOptions, statusLabel, useGoalsLogic } from './goals.logic'

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
    <form v-if="panel === 'goal'" class="band stack editor goal-drawer" role="dialog" aria-modal="true" aria-label="新建目标" tabindex="-1" @submit.prevent="createGoal">
      <button type="button" class="drawer-close secondary" :disabled="busy" @click="cancelGoal" aria-label="关闭目标编辑">关闭</button>
      <div>
        <p class="eyebrow">目标定义</p>
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
            <input v-model.number="task.estimatedMinutes" :disabled="busy" type="number" min="5" max="60" required>
          </label>
          <label>
            <span class="sr-only">起步任务 {{ index + 1 }} 难度</span>
            <select v-model.number="task.difficulty" :disabled="busy">
              <option :value="1">难度 1</option>
              <option :value="2">难度 2</option>
              <option :value="3">难度 3</option>
            </select>
          </label>
        </div>
      </fieldset>
      <div class="actions">
        <button class="primary" :disabled="busy">{{ busy ? '正在保存…' : '保存目标' }}</button>
        <button class="secondary" type="button" :disabled="busy" @click="cancelGoal">取消</button>
      </div>
    </form>

    <form v-else-if="panel === 'task'" class="band stack task-builder goal-drawer" role="dialog" aria-modal="true" aria-label="添加周期任务" tabindex="-1" @submit.prevent="createTask">
      <button type="button" class="drawer-close secondary" :disabled="busy" @click="panel = null" aria-label="关闭任务编辑">关闭</button>
      <div class="task-builder-head">
        <div>
          <p class="eyebrow">周期任务</p>
          <h2>添加任务</h2>
        </div>
        <span v-if="presetDraw" class="refresh-quota">今日还可换 {{ presetDraw.refreshesRemaining }} 次</span>
      </div>

      <div class="role-tabs" aria-label="选择任务场景">
        <button
          v-for="role in roles"
          :key="role.code"
          type="button"
          :class="{ active: selectedRole === role.code }"
          :aria-pressed="selectedRole === role.code"
          @click="chooseRole(role)"
        >{{ role.name }}</button>
      </div>
      <div class="preset-toolbar">
        <strong>{{ roleNames[selectedRole] }}任务模板</strong>
        <button
          class="secondary refresh-button"
          type="button"
          :disabled="presetsLoading || !presetDraw?.refreshesRemaining"
          @click="loadPresets(true)"
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
          @click="choosePreset(preset)"
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
            <select id="task-goal" v-model="taskForm.goalPublicId" required @change="syncTaskPeriod()">
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
            <label for="task-difficulty">难度（1-3）</label>
            <input id="task-difficulty" v-model.number="taskForm.difficulty" type="number" min="1" max="3" required>
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
        <button class="secondary" type="button" @click="panel = null">取消</button>
      </div>
    </form>

    <section class="workspace" aria-label="目标任务工作区">
      <div class="goal-column">
        <div class="section-head">
          <div>
            <p class="eyebrow">我的方向</p>
            <h2>目标</h2>
          </div>
          <div class="stack-arrows">
            <button class="icon-button" type="button" aria-label="上一个目标" :disabled="!canPrevGoals" @click="goalsPrev">
              <ChevronLeft :size="17" />
            </button>
            <span class="stack-count" aria-live="polite">{{ goals.length ? goalsCurrent + 1 : 0 }} / {{ goals.length }}</span>
            <button class="icon-button" type="button" aria-label="下一个目标" :disabled="!canNextGoals" @click="goalsNext">
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
                  <button v-if="goal.status === 'ACTIVE'" class="secondary" type="button" @click="setGoalStatus(goal, 'pause')">
                    <Pause :size="16" />暂停目标
                  </button>
                  <button v-else class="secondary" type="button" @click="setGoalStatus(goal, 'resume')">
                    <Play :size="16" />恢复目标
                  </button>
                  <button class="secondary" type="button" @click="setGoalStatus(goal, 'complete')">
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
            <button v-if="goal.status === 'ACTIVE'" class="secondary add-goal-task" type="button" @click="openTask(goal.publicId)">
              <ListPlus :size="16" />
              添加任务
            </button>
          </article>
        </div>
        <div v-else class="empty goal-empty">
          <h3>还没有目标</h3>
          <p>先定义一个 14-84 天的清晰结果。</p>
          <button class="primary" type="button" @click="openGoal"><Plus :size="17" />新建目标</button>
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
                @click="setTaskActive(task, !task.active)"
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
          <button v-if="currentGoal.status === 'ACTIVE'" class="secondary" type="button" @click="openTask(currentGoal.publicId)">
            <ListPlus :size="16" />
            添加任务
          </button>
        </div>
        <p v-else class="empty">创建目标后即可添加任务。</p>
      </div>
    </section>

    <section class="template-band band" aria-labelledby="template-title">
      <div class="template-head">
        <div>
          <p class="eyebrow">目标模板</p>
          <h2 id="template-title">从熟悉的场景开始</h2>
        </div>
        <span>选择后可继续调整</span>
      </div>
      <div class="template-grid">
        <button
          v-for="template in goalTemplates"
          :key="template.roleCode"
          type="button"
          class="template-card"
          @click="applyGoalTemplate(template)"
        >
          <span class="template-icon"><component :is="template.icon" :size="20" /></span>
          <span class="template-copy">
            <strong>{{ template.title }}</strong>
            <small>{{ template.description }}</small>
          </span>
          <span class="template-tasks">
            <span v-for="task in template.tasks" :key="task">{{ task }}</span>
          </span>
        </button>
      </div>
    </section>
  </section>
</template>

<style scoped>
.editor,
.task-builder {
  gap: 16px;
  margin-bottom: 4px;
}

.editor h2,
.task-builder h2,
.section-head h2,
.template-head h2 {
  margin: 0;
  font-size: 18px;
}

.support-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  max-width: 680px;
  margin: 7px 0 0;
  color: var(--primary);
  line-height: 1.65;
}

.support-line svg {
  flex: none;
  margin-top: 4px;
}

.ai-starter-tasks {
  display: grid;
  gap: 9px;
  margin: 0;
  padding: 13px;
  border: 1px solid color-mix(in srgb, var(--primary) 24%, var(--border));
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--primary-soft) 34%, var(--surface));
}

.ai-starter-tasks legend {
  padding: 0 5px;
  color: var(--primary-strong);
  font-size: 14px;
  font-weight: 750;
}

.ai-starter-task {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 84px 100px;
  gap: 8px;
}

.ai-starter-task label,
.ai-starter-task input,
.ai-starter-task select {
  min-width: 0;
}

.ai-starter-task input,
.ai-starter-task select {
  width: 100%;
  min-height: var(--control);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--ink);
  padding: 9px 10px;
}

.ai-starter-task input:hover,
.ai-starter-task select:hover {
  border-color: color-mix(in srgb, var(--primary) 30%, var(--border));
}

.form-grid {
  display: grid;
  gap: 14px;
}

.two-columns,
.task-identity-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.task-property-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.task-builder-head,
.preset-toolbar,
.template-head,
.section-head,
.goal-top,
.task-row-main,
.goal-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.goal-more { position: relative; }
.goal-more summary { display: grid; place-items: center; min-width: 62px; height: 36px; padding: 0 12px; border: 1px solid var(--border); border-radius: var(--radius); cursor: pointer; list-style: none; font-size: 13px; font-weight: 650; color: var(--muted); }
.goal-more summary::-webkit-details-marker { display: none; }
.goal-more summary::marker { content: ''; }
.goal-more > div { position: absolute; right: 0; top: 40px; z-index: 8; width: 168px; padding: 6px; display: grid; gap: 4px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow); }
.goal-more > div button { justify-content: flex-start; border: 0; min-height: 38px; }
.task-column-note { margin: 6px 0 0; color: var(--muted); font-size: 13px; line-height: 1.6; }
.task-state-button { min-height: 36px; padding: 0 12px; font-size: 13px; }

.refresh-quota,
.template-head > span,
.task-count {
  color: var(--muted);
  font-size: 13px;
  white-space: nowrap;
}

.role-tabs {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-muted);
}

.role-tabs button {
  min-width: 0;
  border: 0;
  background: transparent;
  color: var(--muted);
  padding: 0 8px;
}

.role-tabs button.active {
  background: var(--surface);
  color: var(--primary);
  box-shadow: var(--shadow-soft);
}

.refresh-button {
  flex: none;
}

.preset-list {
  display: grid;
  gap: 8px;
}

.preset-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 18px;
  min-height: 76px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--ink);
  text-align: left;
}

.preset-item:hover {
  border-color: color-mix(in srgb, var(--primary) 34%, var(--border));
  background: var(--surface-raised);
}

.preset-item.selected {
  border-color: color-mix(in srgb, var(--accent) 55%, var(--border));
  background: color-mix(in srgb, var(--accent) 8%, var(--surface));
  box-shadow: inset 3px 0 var(--accent);
}

.preset-main {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.preset-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-weight: 700;
}

.preset-title svg {
  flex: none;
  color: var(--accent);
}

.preset-main small {
  overflow-wrap: anywhere;
  color: var(--muted);
  line-height: 1.45;
}

.preset-meta {
  display: grid;
  grid-template-columns: repeat(2, auto);
  gap: 4px 12px;
  color: var(--muted);
  font-size: 12px;
  text-align: right;
}

.preset-meta b {
  color: var(--amber);
}

.task-fields {
  display: grid;
  gap: 14px;
  padding-top: 4px;
}

.compact-error {
  margin: 0;
}

.preset-loading {
  padding: 24px 0;
  text-align: center;
  color: var(--muted);
}

.workspace {
  display: grid;
  grid-template-columns: minmax(0, .9fr) minmax(380px, 1.25fr);
  gap: 34px;
  padding: 20px 0 28px;
  border-top: 1px solid var(--border);
}

.goal-column,
.task-column {
  min-width: 0;
}

.stack-arrows {
  display: flex;
  align-items: center;
  gap: 6px;
}

.stack-arrows .icon-button,
.goal-actions .icon-button,
.task-state-button {
  border: 1px solid var(--border);
}

.stack-count {
  min-width: 46px;
  text-align: center;
  color: var(--muted);
  font-size: 12px;
  font-weight: 700;
}

.goal-stack {
  margin-top: 12px;
}

.goal-card {
  display: none;
  min-height: 310px;
  padding: 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.goal-card.current {
  display: block;
}

.goal-card h3 {
  margin: 18px 0 8px;
  font-size: 19px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.goal-card > p {
  min-height: 76px;
  margin: 0;
  color: var(--muted);
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.goal-footer {
  align-items: flex-start;
  margin-top: 16px;
  padding-top: 13px;
  border-top: 1px solid var(--surface-muted);
  color: var(--muted);
  font-size: 12px;
}

.goal-footer span:first-child,
.task-meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.add-goal-task {
  width: 100%;
  margin-top: 18px;
}

.complete-goal {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 34%, var(--border)) !important;
}

.status[data-status='PAUSED'],
.task-row.paused .status {
  color: var(--amber);
}

.status[data-status='COMPLETED'] {
  color: var(--accent);
}

.task-list {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}

.task-row {
  padding: 14px 15px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: 0 5px 16px rgb(71 54 44 / 6%);
}

.task-row.paused {
  border-left-color: var(--amber);
  opacity: .76;
}

.task-row-title {
  min-width: 0;
}

.task-row h3 {
  margin: 4px 0 0;
  font-size: 16px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.task-row > p {
  margin: 9px 0 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.task-state-button {
  flex: none;
}

.task-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px 14px;
  margin-top: 13px;
  padding-top: 11px;
  border-top: 1px solid var(--surface-muted);
  color: var(--muted);
  font-size: 12px;
}

.task-meta svg {
  flex: none;
  color: var(--accent);
}

.task-empty,
.goal-empty {
  display: grid;
  justify-items: center;
  gap: 8px;
  margin-top: 12px;
  border: 1px dashed var(--border);
  border-radius: var(--radius);
  padding: 38px 18px;
}

.task-empty h3,
.goal-empty h3 {
  margin: 0;
  color: var(--ink);
  font-size: 16px;
}

.task-empty svg {
  color: var(--accent);
}

.template-band {
  padding-top: 26px;
}

.template-head {
  align-items: flex-end;
  margin-bottom: 14px;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.template-card {
  min-height: 190px;
  display: grid;
  grid-template-rows: auto auto 1fr;
  gap: 12px;
  align-items: start;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--ink);
  text-align: left;
}

.template-card:hover {
  border-color: color-mix(in srgb, var(--primary) 34%, var(--border));
  box-shadow: var(--shadow-soft);
}

.template-icon {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--border));
  border-radius: var(--radius);
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 8%, var(--surface));
}

.template-copy {
  display: grid;
  gap: 7px;
}

.template-copy strong {
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.template-copy small {
  color: var(--muted);
  line-height: 1.5;
}

.template-tasks {
  align-self: end;
  display: grid;
  gap: 5px;
  color: var(--muted);
  font-size: 12px;
}

.template-tasks span {
  padding-left: 9px;
  border-left: 2px solid color-mix(in srgb, var(--amber) 42%, var(--border));
}

.spinning {
  animation: spin .8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: no-preference) {
  .goal-card.current,
  .task-row,
  .template-card,
  .preset-item {
    animation: item-enter var(--motion-medium) ease-out both;
  }
}

@keyframes item-enter {
  from { opacity: 0; transform: translateY(7px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 980px) {
  .task-property-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .template-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .workspace {
    grid-template-columns: 1fr;
  }

  .goal-card {
    min-height: 290px;
  }
}

@media (max-width: 700px) {
  .page-head {
    flex-direction: column;
  }

  .page-head .actions {
    width: 100%;
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .page-head .actions button {
    width: 100%;
    min-width: 0;
  }

  .two-columns,
  .task-identity-grid,
  .task-property-grid,
  .task-meta {
    grid-template-columns: 1fr;
  }

  .ai-starter-task {
    grid-template-columns: minmax(0, 1fr) 84px;
  }

  .ai-starter-task label:last-child {
    grid-column: 1 / -1;
  }

  .role-tabs {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .preset-item {
    grid-template-columns: 1fr;
  }

  .preset-meta {
    justify-content: start;
    text-align: left;
  }
}

@media (max-width: 520px) {
  .task-builder-head,
  .preset-toolbar,
  .template-head,
  .goal-footer {
    align-items: flex-start;
    flex-direction: column;
    gap: 7px;
  }

  .template-grid {
    grid-template-columns: 1fr;
  }

  .goal-card {
    min-height: 360px;
  }

  .editor > .actions,
  .task-builder > .actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .editor > .actions button,
  .task-builder > .actions button,
  .refresh-button {
    width: 100%;
    min-width: 0;
  }
}
.goals-page .workspace { gap: 24px; border-top: 0; padding-top: 0; }
.goal-column, .task-column { padding: 22px; border: 1px solid var(--border); background: var(--surface); border-radius: var(--radius-panel); }
.goal-column { background: var(--surface-muted); }
.goals-page .editor, .goals-page .task-builder { padding: 28px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-panel); box-shadow: var(--shadow); margin-bottom: 28px; }
.goal-card { border-radius: var(--radius-panel); box-shadow: none; }
.template-bank { margin-top: 24px; }
@media (max-width: 760px) { .goal-column, .task-column { padding: 16px; } .goals-page .editor, .goals-page .task-builder { padding: 18px; } }
.goals-page:has(.goal-drawer) { animation: none; }
.goals-page .goal-drawer { position: fixed; inset: 16px 16px 16px auto; z-index: 51; width: min(760px, calc(100vw - 32px)); max-height: calc(100dvh - 32px); overflow-y: auto; margin: 0; align-content: start; }
.drawer-close { justify-self: end; }
@media (max-width:760px) { .goals-page .goal-drawer { inset: 8px; width: calc(100vw - 16px); max-height: calc(100dvh - 16px); } }
</style>
