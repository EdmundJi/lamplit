<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  BookOpen,
  BriefcaseBusiness,
  CalendarClock,
  Check,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Dumbbell,
  HeartPulse,
  ListPlus,
  Pause,
  Play,
  Plus,
  RefreshCw,
  Sparkles,
} from 'lucide-vue-next'
import { api, type ApiError } from '../../shared/api/client'
import { encouragement } from '../../shared/encouragement'
import { takeGoalDraft, type GoalDraft } from '../ai/goal-draft'

type Goal = {
  publicId: string
  dimensionPublicId: string
  title: string
  description: string
  startDate: string
  endDate: string
  status: string
}
type Dimension = { publicId: string; code: string; name: string }
type Task = {
  publicId: string
  weeklyPlanPublicId: string
  goalPublicId: string
  title: string
  notes: string
  estimatedMinutes: number
  difficulty: number
  rrule: string | null
  plannedLocalTime: string
  activeFrom: string
  activeUntil: string
  active: boolean
  roleCode: string
}
type RoleCode = 'STUDENT' | 'FITNESS_USER' | 'WORKER' | 'EMOTIONAL_SUPPORT_USER'
type Preset = {
  publicId: string
  roleCode: RoleCode
  roleName: string
  name: string
  notes: string
  estimatedMinutes: number
  difficulty: number
  plannedLocalTime: string
  rrule: string
  dimensionCode: string
  dimensionWeight: number
  experienceReward: number
}
type PresetDraw = {
  roleCode: RoleCode
  roleName: string
  localDate: string
  refreshesRemaining: number
  items: Preset[]
}
type GoalTemplate = {
  roleCode: RoleCode
  title: string
  description: string
  dimensionName: string
  durationDays: number
  icon: unknown
  tasks: string[]
}

const roles: { code: RoleCode; name: string; dimension: string }[] = [
  { code: 'STUDENT', name: '学生', dimension: 'KNOWLEDGE' },
  { code: 'FITNESS_USER', name: '健身用户', dimension: 'HEALTH' },
  { code: 'WORKER', name: '打工人', dimension: 'CAREER' },
  { code: 'EMOTIONAL_SUPPORT_USER', name: '情绪支持用户', dimension: 'WELLBEING' },
]
const goalTemplates: GoalTemplate[] = [
  {
    roleCode: 'STUDENT',
    title: '四周完成一轮学习复盘',
    description: '每周完成一次知识整理，并在第 4 周产出一份可回看的复盘笔记。',
    dimensionName: '知识',
    durationDays: 28,
    icon: BookOpen,
    tasks: ['每周整理 1 章要点', '用 25 分钟做错题回看', '周末写 5 行复盘'],
  },
  {
    roleCode: 'FITNESS_USER',
    title: '建立温和的身体照顾习惯',
    description: '以不透支为前提，每周完成 3 次低压力训练或恢复行动。',
    dimensionName: '健康',
    durationDays: 28,
    icon: Dumbbell,
    tasks: ['20 分钟轻训练', '训练后记录感受', '每周安排 1 次恢复'],
  },
  {
    roleCode: 'WORKER',
    title: '推进一个可交付的职场成果',
    description: '把一个工作成果拆成每周可验证的小交付，减少临时抱佛脚。',
    dimensionName: '职业',
    durationDays: 28,
    icon: BriefcaseBusiness,
    tasks: ['明确本周交付物', '每天推进一个 25 分钟片段', '周五整理风险'],
  },
  {
    roleCode: 'EMOTIONAL_SUPPORT_USER',
    title: '建立一套情绪支持流程',
    description: '在四周内形成可重复的自我照顾、求助和复盘方式。',
    dimensionName: '情绪',
    durationDays: 28,
    icon: HeartPulse,
    tasks: ['写下今日状态', '准备可信任联系人', '复盘一次恢复经验'],
  },
]
const roleNames = Object.fromEntries(roles.map(role => [role.code, role.name]))
const rruleOptions = [
  ['', '仅一次'],
  ['FREQ=DAILY', '每天'],
  ['FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR', '每个工作日'],
  ['FREQ=WEEKLY;BYDAY=MO,WE,FR', '每周一、三、五'],
  ['FREQ=WEEKLY;BYDAY=TU,TH', '每周二、四'],
  ['FREQ=WEEKLY;BYDAY=TU,TH,SA', '每周二、四、六'],
  ['FREQ=WEEKLY;BYDAY=WE,SA', '每周三、六'],
  ['FREQ=WEEKLY;BYDAY=MO,TH', '每周一、四'],
  ['FREQ=WEEKLY;BYDAY=TU,FR', '每周二、五'],
  ['FREQ=WEEKLY;BYDAY=MO,WE', '每周一、三'],
  ['FREQ=WEEKLY;BYDAY=MO,FR', '每周一、五'],
  ['FREQ=WEEKLY;BYDAY=FR', '每周五'],
  ['FREQ=WEEKLY;BYDAY=TH', '每周四'],
  ['FREQ=WEEKLY;BYDAY=SA', '每周六'],
  ['FREQ=WEEKLY;BYDAY=SU', '每周日'],
  ['FREQ=WEEKLY;BYDAY=TU,SA', '每周二、六'],
]

const goals = ref<Goal[]>([])
const dimensions = ref<Dimension[]>([])
const tasks = ref<Task[]>([])
const loading = ref(true)
const panel = ref<'goal' | 'task' | null>(null)
const error = ref('')
const busy = ref(false)
const feedback = ref<{ tone: 'support' | 'celebrate'; text: string } | null>(null)
const goalPrompt = ref(encouragement('goalDraft'))
const selectedRole = ref<RoleCode>('STUDENT')
const presetDraw = ref<PresetDraw | null>(null)
const presetsLoading = ref(false)
const presetError = ref('')
const goalsCurrent = ref(0)

const goalForm = reactive({
  dimensionPublicId: '',
  title: '',
  description: '',
  startDate: today(),
  endDate: plusDays(today(), 27),
})
const taskForm = reactive({
  goalPublicId: '',
  title: '',
  notes: '',
  estimatedMinutes: 25,
  difficulty: 2,
  rrule: 'FREQ=DAILY',
  plannedLocalTime: '19:00',
  activeFrom: today(),
  activeUntil: '',
  sourceTemplatePublicId: '',
  roleCode: 'STUDENT' as RoleCode,
  dimensionCode: 'KNOWLEDGE',
  dimensionWeight: 10,
})

const currentGoal = computed(() => goals.value[goalsCurrent.value] ?? null)
const currentTasks = computed(() => currentGoal.value
  ? tasks.value.filter(task => task.goalPublicId === currentGoal.value?.publicId)
  : [])
const activeGoals = computed(() => goals.value.filter(goal => goal.status === 'ACTIVE'))
const canPrevGoals = computed(() => goalsCurrent.value > 0)
const canNextGoals = computed(() => goalsCurrent.value < goals.value.length - 1)

function formatLocalDate(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function today() {
  return formatLocalDate(new Date())
}

function plusDays(value: string, days: number) {
  const date = new Date(`${value}T00:00:00`)
  date.setDate(date.getDate() + days)
  return formatLocalDate(date)
}

function statusLabel(value: string) {
  return ({ ACTIVE: '进行中', PAUSED: '已暂停', COMPLETED: '已完成', DRAFT: '草稿' } as Record<string, string>)[value] ?? value
}

function rruleLabel(value: string | null) {
  return rruleOptions.find(option => option[0] === (value ?? ''))?.[1] ?? '自定义周期'
}

function goalsPrev() {
  goalsCurrent.value = Math.max(0, goalsCurrent.value - 1)
}

function goalsNext() {
  goalsCurrent.value = Math.min(goals.value.length - 1, goalsCurrent.value + 1)
}

async function load() {
  loading.value = true
  const selectedGoalId = currentGoal.value?.publicId
  try {
    const [goalRows, dimensionRows, taskRows] = await Promise.all([
      api.get<Goal[]>('/goals'),
      api.get<Dimension[]>('/dimensions'),
      api.get<Task[]>('/tasks'),
    ])
    goals.value = goalRows
    dimensions.value = dimensionRows
    tasks.value = taskRows
    const selectedIndex = selectedGoalId ? goalRows.findIndex(goal => goal.publicId === selectedGoalId) : -1
    goalsCurrent.value = selectedIndex >= 0 ? selectedIndex : 0
    goalForm.dimensionPublicId ||= dimensionRows[0]?.publicId ?? ''
  } catch {
    error.value = '目标与任务暂时无法加载'
  } finally {
    loading.value = false
  }
}

function openGoal() {
  goalPrompt.value = encouragement('goalDraft')
  feedback.value = null
  panel.value = 'goal'
}

function syncTaskPeriod(goalPublicId = taskForm.goalPublicId) {
  const goal = goals.value.find(item => item.publicId === goalPublicId)
  if (!goal) return
  const start = today() < goal.startDate || today() > goal.endDate ? goal.startDate : today()
  taskForm.activeFrom = start
  taskForm.activeUntil = goal.endDate
}

function resetTaskForm(goalPublicId: string) {
  const role = roles.find(item => item.code === selectedRole.value) ?? roles[0]
  taskForm.goalPublicId = goalPublicId
  taskForm.title = ''
  taskForm.notes = ''
  taskForm.estimatedMinutes = 25
  taskForm.difficulty = 2
  taskForm.rrule = 'FREQ=DAILY'
  taskForm.plannedLocalTime = '19:00'
  taskForm.sourceTemplatePublicId = ''
  taskForm.roleCode = role.code
  taskForm.dimensionCode = role.dimension
  taskForm.dimensionWeight = 10
  syncTaskPeriod(goalPublicId)
}

async function openTask(goalPublicId?: string) {
  const target = activeGoals.value.find(goal => goal.publicId === goalPublicId)
    ?? (currentGoal.value?.status === 'ACTIVE' ? currentGoal.value : activeGoals.value[0])
  if (!target) {
    error.value = '请先创建一个进行中的目标'
    return
  }
  error.value = ''
  feedback.value = null
  resetTaskForm(target.publicId)
  panel.value = 'task'
  await loadPresets(false)
}

async function loadPresets(refresh: boolean) {
  presetsLoading.value = true
  presetError.value = ''
  try {
    presetDraw.value = refresh
      ? await api.post<PresetDraw>(`/task-presets/refresh?role=${selectedRole.value}`)
      : await api.get<PresetDraw>(`/task-presets?role=${selectedRole.value}`)
  } catch (err: any) {
    presetError.value = err?.code === 'TASK_PRESET_REFRESH_LIMIT'
      ? '今天的换一批机会已经用完了'
      : '任务模板暂时无法加载，请稍后重试'
  } finally {
    presetsLoading.value = false
  }
}

async function chooseRole(role: typeof roles[number]) {
  selectedRole.value = role.code
  taskForm.roleCode = role.code
  taskForm.dimensionCode = role.dimension
  taskForm.dimensionWeight = 10
  taskForm.sourceTemplatePublicId = ''
  await loadPresets(false)
}

function choosePreset(preset: Preset) {
  taskForm.title = preset.name
  taskForm.notes = preset.notes
  taskForm.estimatedMinutes = preset.estimatedMinutes
  taskForm.difficulty = preset.difficulty
  taskForm.rrule = preset.rrule
  taskForm.plannedLocalTime = preset.plannedLocalTime.slice(0, 5)
  taskForm.sourceTemplatePublicId = preset.publicId
  taskForm.roleCode = preset.roleCode
  taskForm.dimensionCode = preset.dimensionCode
  taskForm.dimensionWeight = preset.dimensionWeight
}

function applyGoalTemplate(template: GoalTemplate) {
  const dimension = dimensions.value.find(item => String(item.name).includes(template.dimensionName)) ?? dimensions.value[0]
  goalForm.dimensionPublicId = dimension?.publicId ?? goalForm.dimensionPublicId
  goalForm.title = template.title
  goalForm.description = template.description
  goalForm.startDate = today()
  goalForm.endDate = plusDays(goalForm.startDate, template.durationDays - 1)
  selectedRole.value = template.roleCode
  goalPrompt.value = `可以从这些行动开始：${template.tasks.join('、')}。`
  feedback.value = null
  panel.value = 'goal'
}

function applyAiGoalDraft(draft: GoalDraft) {
  const dimension = dimensions.value.find(item => item.code === draft.dimensionCode) ?? dimensions.value[0]
  goalForm.dimensionPublicId = dimension?.publicId ?? goalForm.dimensionPublicId
  goalForm.title = draft.title
  goalForm.description = draft.description
  goalForm.startDate = today()
  goalForm.endDate = plusDays(goalForm.startDate, draft.durationDays - 1)
  goalPrompt.value = `本周重点：${draft.weeklyFocus} 起步任务：${draft.starterTasks.map(task => task.title).join('、')}。`
  feedback.value = { tone: 'support', text: 'AI 目标草案已填入。你可以继续修改，确认后再保存。' }
  panel.value = 'goal'
}

function friendlyGoalError(value: unknown, fallback: string) {
  const code = (value as Partial<ApiError> | null)?.code
  const messages: Record<string, string> = {
    ACTIVE_GOAL_LIMIT: '同时进行的活跃目标最多 3 个，请先完成或暂停一个目标再创建。',
    INVALID_GOAL_DURATION: '目标时长需在 14 到 84 天之间，请检查起止日期。',
    INVALID_GOAL_TRANSITION: '当前状态下不能执行该操作，请刷新后再试。',
  }
  return messages[code ?? ''] ?? fallback
}

function friendlyTaskError(value: unknown) {
  const code = (value as Partial<ApiError> | null)?.code
  const messages: Record<string, string> = {
    TASK_OUTSIDE_GOAL: '任务周期需要在目标的起止日期内。',
    INVALID_TASK_DATES: '任务结束日期不能早于开始日期。',
    GOAL_NOT_FOUND: '这个目标当前不能添加任务，请选择进行中的目标。',
    INVALID_RRULE: '任务周期设置无效，请重新选择。',
  }
  return messages[code ?? ''] ?? '任务未保存，请检查周期、日期和其他属性'
}

async function createGoal() {
  busy.value = true
  error.value = ''
  try {
    const created = await api.post<Goal>('/goals', goalForm)
    panel.value = null
    feedback.value = { tone: 'support', text: encouragement('goalCreated') }
    await load()
    const index = goals.value.findIndex(goal => goal.publicId === created.publicId)
    if (index >= 0) goalsCurrent.value = index
  } catch (err) {
    error.value = friendlyGoalError(err, '目标未保存，请检查日期与内容')
  } finally {
    busy.value = false
  }
}

async function createTask() {
  busy.value = true
  error.value = ''
  try {
    await api.post('/tasks', {
      goalPublicId: taskForm.goalPublicId,
      title: taskForm.title,
      notes: taskForm.notes,
      estimatedMinutes: taskForm.estimatedMinutes,
      difficulty: taskForm.difficulty,
      rrule: taskForm.rrule || null,
      plannedLocalTime: taskForm.plannedLocalTime,
      activeFrom: taskForm.activeFrom,
      activeUntil: taskForm.activeUntil,
      sourceTemplatePublicId: taskForm.sourceTemplatePublicId || null,
      roleCode: taskForm.roleCode,
      dimensionWeights: { [taskForm.dimensionCode]: taskForm.dimensionWeight },
    })
    panel.value = null
    feedback.value = { tone: 'support', text: '任务与周期已保存，并已排入对应日期。' }
    await load()
  } catch (err) {
    error.value = friendlyTaskError(err)
  } finally {
    busy.value = false
  }
}

async function setTaskActive(task: Task, active: boolean) {
  error.value = ''
  try {
    const updated = await api.post<Task>(`/tasks/${task.publicId}/${active ? 'resume' : 'pause'}`)
    task.active = updated.active
  } catch {
    error.value = '任务状态暂时无法更新，请重试'
  }
}

async function setGoalStatus(goal: Goal, action: string) {
  try {
    goal.status = (await api.post<Goal>(`/goals/${goal.publicId}/${action}`)).status
    if (action === 'complete') {
      feedback.value = { tone: 'celebrate', text: encouragement('goalCompleted') }
    }
  } catch (err) {
    error.value = friendlyGoalError(err, '目标状态暂时无法更新，请重试')
  }
}

onMounted(async () => {
  await load()
  const draft = takeGoalDraft()
  if (draft) applyAiGoalDraft(draft)
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <p class="eyebrow">成长路径</p>
        <h1>目标与任务</h1>
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

    <form v-if="panel === 'goal'" class="band stack editor" @submit.prevent="createGoal">
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
      <div class="actions">
        <button class="primary" :disabled="busy">保存目标</button>
        <button class="secondary" type="button" @click="panel = null">取消</button>
      </div>
    </form>

    <form v-else-if="panel === 'task'" class="band stack task-builder" @submit.prevent="createTask">
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
              <div class="goal-actions">
                <button
                  v-if="goal.status === 'ACTIVE'"
                  class="icon-button"
                  type="button"
                  title="暂停目标"
                  aria-label="暂停目标"
                  @click="setGoalStatus(goal, 'pause')"
                ><Pause :size="17" /></button>
                <button
                  v-else-if="goal.status === 'PAUSED'"
                  class="icon-button"
                  type="button"
                  title="恢复目标"
                  aria-label="恢复目标"
                  @click="setGoalStatus(goal, 'resume')"
                ><Play :size="17" /></button>
                <button
                  v-if="goal.status === 'ACTIVE' || goal.status === 'PAUSED'"
                  class="icon-button complete-goal"
                  type="button"
                  title="完成目标"
                  aria-label="完成目标"
                  @click="setGoalStatus(goal, 'complete')"
                ><CheckCircle2 :size="18" /></button>
              </div>
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
            <h2>{{ currentGoal ? '任务' : '待添加任务' }}</h2>
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
                class="icon-button task-state-button"
                type="button"
                :title="task.active ? '暂停任务' : '恢复任务'"
                :aria-label="task.active ? '暂停任务' : '恢复任务'"
                @click="setTaskActive(task, !task.active)"
              >
                <Pause v-if="task.active" :size="16" />
                <Play v-else :size="16" />
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
</style>
